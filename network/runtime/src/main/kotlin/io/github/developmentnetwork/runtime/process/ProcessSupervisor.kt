package io.github.developmentnetwork.runtime.process

import io.github.developmentnetwork.runtime.model.ProcessIdentity
import java.time.Duration

/** A process and the identity captured at the instant this runtime started owning it. */
data class OwnedProcess(
    val process: Process,
    val identity: ProcessIdentity,
    /** The actual child stdin channel; its lifetime is owned by the caller. */
    val stdin: java.io.OutputStream,
) {
    val handle: ProcessHandle get() = process.toHandle()
    val pid: Long get() = identity.pid
    val startIdentity get() = identity.startInstant
}

/** Outcome of an owner-verified process termination attempt. */
typealias TerminationResult = ProcessSupervisor.TerminationResult

/** Direct-JDK process launcher and owner-verified supervisor. */
class ProcessSupervisor(
    val identityReader: ProcessIdentityReader = ProcessIdentityReader(),
    private val processHandleLookup: (Long) -> java.util.Optional<ProcessHandle> = ProcessHandle::of,
) {
    enum class TerminationResult(
        val terminated: Boolean,
        val forceEscalated: Boolean,
        val ownerMatched: Boolean,
    ) {
        ALREADY_EXITED(true, false, true),
        GRACEFUL(true, false, true),
        FORCED(true, true, true),
        NOT_TERMINATED(false, true, true),
        INTERRUPTED(false, false, true),
        NOT_OWNED(false, false, false),
        ;

        val graceful: Boolean get() = this == GRACEFUL
        val forced: Boolean get() = forceEscalated
        val success: Boolean get() = terminated
    }

    /** Launch directly and retain the process-provided child stdin stream. */
    fun launch(command: List<String>, cwd: java.nio.file.Path): OwnedProcess {
        require(command.isNotEmpty()) { "Process command must not be empty" }
        require(java.nio.file.Files.isDirectory(cwd)) { "Process working directory does not exist: $cwd" }

        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        return try {
            val identity = identityReader.capture(process, cwd)
            // A lease without every identity component cannot ever be safely
            // signalled. Fail launch closed rather than returning an unusable owner.
            check(identity.startInstant != null && identity.executable != null && identity.workingDirectory != null) {
                "Process identity metadata is unavailable"
            }
            OwnedProcess(
                process = process,
                identity = identity,
                stdin = process.outputStream,
            )
        } catch (error: Throwable) {
            cleanupFailedLaunch(process)
            throw error
        }
    }

    /** Wait for process exit without turning interruption into a lost interrupt. */
    fun await(process: OwnedProcess) {
        try {
            process.process.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Gracefully terminate the owned process tree, then force matching identities only
     * after [timeout] expires. Descendants are refreshed throughout both phases, so a
     * child spawned after the first snapshot is still owned and supervised.
     */
    fun terminate(process: OwnedProcess, timeout: Duration): TerminationResult {
        require(!timeout.isNegative) { "Termination timeout must not be negative" }
        if (!process.process.isAlive) {
            // A dead root cannot be used to perform a final descendant
            // observation. Its children may already have been reparented, so
            // no successful termination claim is safe.
            return TerminationResult.NOT_OWNED
        }
        if (!safeMatches(process.identity)) return TerminationResult.NOT_OWNED
        if (wasInterrupted()) return TerminationResult.INTERRUPTED

        val root = process.process.toHandle()
        val tracked = LinkedHashMap<Long, TrackedProcess>()
        val unknown = LinkedHashMap<Long, ProcessHandle>()
        val discovery = DiscoveryState()

        val initialRefresh = refreshDescendants(root, process.identity, tracked, unknown)
        discovery.record(initialRefresh, unknown)
        when (initialRefresh) {
            RefreshResult.ROOT_NOT_OWNED -> return TerminationResult.NOT_OWNED
            RefreshResult.INTERRUPTED -> return TerminationResult.INTERRUPTED
            RefreshResult.INDETERMINATE,
            RefreshResult.SUCCESS,
            -> Unit
        }

        val gracefulSignal = signalGracefully(root, process.identity, tracked)
        when (gracefulSignal.status) {
            SignalStatus.INTERRUPTED -> return TerminationResult.INTERRUPTED
            SignalStatus.ROOT_NOT_OWNED -> return TerminationResult.NOT_OWNED
            SignalStatus.COMPLETE -> Unit
        }
        var rootSignalSent = gracefulSignal.rootSignalSent

        val gracefulWait = waitForTree(
            root,
            process.identity,
            tracked,
            unknown,
            discovery,
            rootSignalSent,
            deadlineNanos(timeout),
            forcePhase = false,
        )
        if (gracefulWait.interrupted) return TerminationResult.INTERRUPTED
        if (gracefulWait.completed) return TerminationResult.GRACEFUL
        if (gracefulWait.rootNotOwned) return TerminationResult.NOT_OWNED

        // Never let a graceful interruption trigger force escalation. This check
        // deliberately occurs before any force refresh or signal.
        if (wasInterrupted()) return TerminationResult.INTERRUPTED
        val forceRefresh = refreshDescendants(root, process.identity, tracked, unknown)
        discovery.record(forceRefresh, unknown)
        when (forceRefresh) {
            RefreshResult.ROOT_NOT_OWNED -> return TerminationResult.NOT_OWNED
            RefreshResult.INTERRUPTED -> return TerminationResult.INTERRUPTED
            RefreshResult.INDETERMINATE,
            RefreshResult.SUCCESS,
            -> Unit
        }
        if (wasInterrupted()) return TerminationResult.INTERRUPTED

        val forceSignal = signalForcibly(root, process.identity, tracked)
        when (forceSignal.status) {
            SignalStatus.INTERRUPTED -> return TerminationResult.INTERRUPTED
            SignalStatus.ROOT_NOT_OWNED -> {
                return if (!safeAlive(root)) {
                    TerminationResult.NOT_TERMINATED
                } else {
                    TerminationResult.NOT_OWNED
                }
            }
            SignalStatus.COMPLETE -> Unit
        }
        rootSignalSent = rootSignalSent || forceSignal.rootSignalSent

        val forceWait = waitForTree(
            root,
            process.identity,
            tracked,
            unknown,
            discovery,
            rootSignalSent,
            safeAdd(System.nanoTime(), FORCE_WAIT_NANOS),
            forcePhase = true,
        )
        if (forceWait.interrupted) return TerminationResult.INTERRUPTED
        if (forceWait.completed) return TerminationResult.FORCED
        if (forceWait.rootNotOwned) {
            return if (!safeAlive(root)) {
                TerminationResult.NOT_TERMINATED
            } else {
                TerminationResult.NOT_OWNED
            }
        }

        // A force request is not evidence of termination. Report failure until the
        // root and every discovered or indeterminate descendant have been observed dead.
        return TerminationResult.NOT_TERMINATED
    }

    private fun signalGracefully(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: Map<Long, TrackedProcess>,
    ): SignalResult {
        for (trackedProcess in tracked.values.toList().asReversed()) {
            if (wasInterrupted()) return SignalResult(SignalStatus.INTERRUPTED)
            if (!rootOwned(root, rootIdentity)) return SignalResult(SignalStatus.ROOT_NOT_OWNED)
            if (safeAlive(trackedProcess.handle) && safeMatches(trackedProcess.handle, trackedProcess.identity)) {
                runCatching { trackedProcess.handle.destroy() }
            }
        }
        if (wasInterrupted()) return SignalResult(SignalStatus.INTERRUPTED)
        if (!rootOwned(root, rootIdentity)) return SignalResult(SignalStatus.ROOT_NOT_OWNED)
        val rootSignalSent = runCatching { root.destroy() }.getOrDefault(false)
        return SignalResult(SignalStatus.COMPLETE, rootSignalSent)
    }

    private fun signalForcibly(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: Map<Long, TrackedProcess>,
    ): SignalResult {
        for (trackedProcess in tracked.values.toList().asReversed()) {
            if (wasInterrupted()) return SignalResult(SignalStatus.INTERRUPTED)
            if (!rootOwned(root, rootIdentity)) return SignalResult(SignalStatus.ROOT_NOT_OWNED)
            if (safeAlive(trackedProcess.handle) && safeMatches(trackedProcess.handle, trackedProcess.identity)) {
                runCatching { trackedProcess.handle.destroyForcibly() }
            }
        }
        if (wasInterrupted()) return SignalResult(SignalStatus.INTERRUPTED)
        if (!rootOwned(root, rootIdentity)) return SignalResult(SignalStatus.ROOT_NOT_OWNED)
        val rootSignalSent = runCatching { root.destroyForcibly() }.getOrDefault(false)
        return SignalResult(SignalStatus.COMPLETE, rootSignalSent)
    }

    private fun waitForTree(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: MutableMap<Long, TrackedProcess>,
        unknown: MutableMap<Long, ProcessHandle>,
        discovery: DiscoveryState,
        initialRootSignalSent: Boolean,
        deadline: Long,
        forcePhase: Boolean,
    ): WaitResult {
        var rootSignalSent = initialRootSignalSent
        while (true) {
            if (wasInterrupted()) return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
            if (treeIsDead(root, rootIdentity, tracked, unknown, discovery, rootSignalSent)) {
                return WaitResult(completed = true, interrupted = false, rootNotOwned = false)
            }
            // A dead or mismatched root is never a reason to continue discovering or
            // signalling descendants. It is safe to report completion only when the
            // final complete discovery observation preceded an owned root signal.
            if (!rootOwned(root, rootIdentity)) {
                return if (rootSignalSent && observeTreeDeath(root, rootIdentity, tracked, unknown, discovery) == Observation.DEAD) {
                    WaitResult(completed = true, interrupted = false, rootNotOwned = false)
                } else {
                    WaitResult(completed = false, interrupted = false, rootNotOwned = true)
                }
            }
            when (val refreshed = refreshDescendants(root, rootIdentity, tracked, unknown)) {
                RefreshResult.ROOT_NOT_OWNED -> {
                    discovery.record(refreshed, unknown, rootSignalSent)
                    return if (rootSignalSent &&
                        observeTreeDeath(root, rootIdentity, tracked, unknown, discovery) == Observation.DEAD
                    ) {
                        WaitResult(completed = true, interrupted = false, rootNotOwned = false)
                    } else {
                        WaitResult(completed = false, interrupted = false, rootNotOwned = true)
                    }
                }
                RefreshResult.INTERRUPTED -> {
                    return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
                }
                RefreshResult.SUCCESS,
                RefreshResult.INDETERMINATE,
                -> discovery.record(refreshed, unknown, rootSignalSent)
            }
            if (wasInterrupted()) return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
            if (treeIsDead(root, rootIdentity, tracked, unknown, discovery, rootSignalSent)) {
                return WaitResult(completed = true, interrupted = false, rootNotOwned = false)
            }
            // Revalidate before every graceful/force signal pass, including the pass
            // immediately after descendant discovery.
            if (!rootOwned(root, rootIdentity)) {
                return WaitResult(completed = false, interrupted = false, rootNotOwned = true)
            }
            val signal = if (forcePhase) {
                signalForcibly(root, rootIdentity, tracked)
            } else {
                signalGracefully(root, rootIdentity, tracked)
            }
            when (signal.status) {
                SignalStatus.INTERRUPTED -> {
                    return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
                }
                SignalStatus.ROOT_NOT_OWNED -> {
                    return if (rootSignalSent &&
                        observeTreeDeath(root, rootIdentity, tracked, unknown, discovery) == Observation.DEAD
                    ) {
                        WaitResult(completed = true, interrupted = false, rootNotOwned = false)
                    } else {
                        WaitResult(completed = false, interrupted = false, rootNotOwned = true)
                    }
                }
                SignalStatus.COMPLETE -> {
                    rootSignalSent = rootSignalSent || signal.rootSignalSent
                }
            }
            if (treeIsDead(root, rootIdentity, tracked, unknown, discovery, rootSignalSent)) {
                return WaitResult(completed = true, interrupted = false, rootNotOwned = false)
            }
            if (System.nanoTime() >= deadline) {
                return WaitResult(completed = false, interrupted = false, rootNotOwned = false)
            }
            if (wasInterrupted()) return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
            }
        }
    }

    /**
     * Return true only when the final complete discovery observation preceded an
     * owned root signal and every known or indeterminate identity is observably dead.
     */
    private fun treeIsDead(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: Map<Long, TrackedProcess>,
        unknown: Map<Long, ProcessHandle>,
        discovery: DiscoveryState,
        allowRootDeath: Boolean,
    ): Boolean {
        if (!discovery.canClaimTermination) return false
        val rootAlive = safeAlive(root)
        if (rootAlive) {
            // Recheck the exact root lease immediately before making an observation
            // about the tree. A replacement root must never produce completion.
            if (!safeMatches(root, rootIdentity)) return false
            return false
        }
        if (!allowRootDeath) return false
        if (unknown.values.any(::safeAlive)) return false
        val states = tracked.values.map(::observedState)
        return states.all { it == ObservedState.DEAD }
    }

    private fun observedState(tracked: TrackedProcess): ObservedState {
        val current = when (val lookup = lookupProcessHandle(tracked.identity.pid)) {
            ProcessLookup.ABSENT -> return ObservedState.DEAD
            ProcessLookup.UNKNOWN -> return ObservedState.UNKNOWN
            is ProcessLookup.PRESENT -> lookup.handle
        }
        if (!safeAlive(current)) return ObservedState.DEAD
        val expectedStart = tracked.identity.startInstant ?: return ObservedState.UNKNOWN
        // A different start instant proves the tracked lease exited and the PID was
        // reused; do not mistake the replacement for an owned descendant.
        val actualStart = runCatching { current.info().startInstant().orElse(null) }.getOrNull()
            ?: return ObservedState.UNKNOWN
        if (actualStart != expectedStart) return ObservedState.UNKNOWN
        return if (safeMatches(current, tracked.identity)) {
            ObservedState.OWNED_ALIVE
        } else {
            ObservedState.UNKNOWN
        }
    }

    /**
     * ProcessHandle.of returning an empty Optional is a confirmed absence. An
     * exception is an indeterminate lookup and must not be treated as process exit.
     */
    private fun lookupProcessHandle(pid: Long): ProcessLookup =
        try {
            val optional = processHandleLookup(pid)
            if (optional.isPresent) ProcessLookup.PRESENT(optional.get()) else ProcessLookup.ABSENT
        } catch (_: Throwable) {
            ProcessLookup.UNKNOWN
        }

    /** Refresh descendants only while the root still owns its exact captured lease. */
    private fun refreshDescendants(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: MutableMap<Long, TrackedProcess>,
        unknown: MutableMap<Long, ProcessHandle>,
    ): RefreshResult {
        if (wasInterrupted()) return RefreshResult.INTERRUPTED
        if (!rootOwned(root, rootIdentity)) return RefreshResult.ROOT_NOT_OWNED
        val handles = runCatching { root.descendants().toList() }.getOrElse {
            return RefreshResult.INDETERMINATE
        }
        if (wasInterrupted()) return RefreshResult.INTERRUPTED
        if (!rootOwned(root, rootIdentity)) return RefreshResult.ROOT_NOT_OWNED

        var captureFailed = false
        for (handle in handles) {
            if (wasInterrupted()) return RefreshResult.INTERRUPTED
            if (!rootOwned(root, rootIdentity)) return RefreshResult.ROOT_NOT_OWNED
            // A descendant that exited between enumeration and capture is a
            // confirmed absence, not a failed identity observation.
            if (!definitelyAlive(handle)) continue
            // A captured descendant lease is kept for the duration of the
            // attempt. Re-capturing it after signaling can observe transiently
            // missing metadata during exit; observedState verifies the saved
            // lease and rejects PID reuse without a second capture.
            if (tracked.containsKey(handle.pid())) continue
            val identity = runCatching {
                identityReader.captureDescendant(
                    handle,
                    rootIdentity.workingDirectory ?: java.nio.file.Path.of("."),
                )
            }.getOrNull()
            if (identity == null ||
                identity.startInstant == null ||
                identity.executable == null ||
                identity.workingDirectory == null
            ) {
                captureFailed = true
                unknown[handle.pid()] = handle
                continue
            }
            unknown.remove(identity.pid)
            val previous = tracked[identity.pid]
            if (previous == null || previous.identity != identity) {
                tracked[identity.pid] = TrackedProcess(handle, identity)
            }
        }
        // An identity capture that failed for an already-dead handle no longer
        // represents a live unverified descendant. The failed observation itself
        // remains sticky through DiscoveryState.
        unknown.entries.removeIf { !safeAlive(it.value) }
        return if (captureFailed) RefreshResult.INDETERMINATE else RefreshResult.SUCCESS
    }

    private fun observeTreeDeath(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: Map<Long, TrackedProcess>,
        unknown: Map<Long, ProcessHandle>,
        discovery: DiscoveryState,
    ): Observation {
        if (!discovery.canClaimTermination) return Observation.NOT_DEAD
        repeat(ROOT_DEATH_OBSERVE_POLLS) {
            if (wasInterrupted()) return Observation.INTERRUPTED
            if (treeIsDead(root, rootIdentity, tracked, unknown, discovery, allowRootDeath = true)) {
                return Observation.DEAD
            }
            if (safeAlive(root)) return Observation.NOT_DEAD
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Observation.INTERRUPTED
            }
        }
        return if (treeIsDead(root, rootIdentity, tracked, unknown, discovery, allowRootDeath = true)) {
            Observation.DEAD
        } else {
            Observation.NOT_DEAD
        }
    }


    private fun rootOwned(root: ProcessHandle, identity: ProcessIdentity): Boolean =
        safeAlive(root) && safeMatches(root, identity)

    private fun safeMatches(identity: ProcessIdentity): Boolean =
        runCatching { identityReader.matches(identity) }.getOrDefault(false)

    private fun safeMatches(handle: ProcessHandle, identity: ProcessIdentity): Boolean =
        runCatching { identityReader.matches(handle, identity) }.getOrDefault(false)

    private fun safeAlive(handle: ProcessHandle): Boolean =
        runCatching { handle.isAlive }.getOrDefault(true)

    private fun definitelyAlive(handle: ProcessHandle): Boolean =
        safeAlive(handle) && runCatching { !handle.onExit().isDone }.getOrDefault(true)

    private fun wasInterrupted(): Boolean {
        if (!Thread.interrupted()) return false
        Thread.currentThread().interrupt()
        return true
    }

    private fun cleanupFailedLaunch(process: Process) {
        var interrupted = false
        process.destroy()
        try {
            if (!process.waitFor(LAUNCH_CLEANUP_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(LAUNCH_CLEANUP_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            interrupted = true
            process.destroyForcibly()
            runCatching { process.waitFor(LAUNCH_CLEANUP_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun deadlineNanos(timeout: Duration): Long {
        val nanos = try {
            timeout.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        return safeAdd(System.nanoTime(), nanos)
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (second >= 0L && first > Long.MAX_VALUE - second) Long.MAX_VALUE
        else if (second < 0L && first < Long.MIN_VALUE - second) Long.MIN_VALUE
        else first + second

    private enum class ObservedState { DEAD, OWNED_ALIVE, UNKNOWN }
    private enum class RefreshResult { SUCCESS, INDETERMINATE, ROOT_NOT_OWNED, INTERRUPTED }
    private enum class SignalStatus { COMPLETE, ROOT_NOT_OWNED, INTERRUPTED }
    private enum class Observation { DEAD, NOT_DEAD, INTERRUPTED }
    private data class SignalResult(
        val status: SignalStatus,
        val rootSignalSent: Boolean = false,
    )

    private sealed interface ProcessLookup {
        data object ABSENT : ProcessLookup
        data object UNKNOWN : ProcessLookup
        data class PRESENT(val handle: ProcessHandle) : ProcessLookup
    }

    private class DiscoveryState {
        var complete: Boolean = false
            private set
        private var uncertain: Boolean = false
        private var finalObservationComplete: Boolean = false

        val canClaimTermination: Boolean
            get() = complete && finalObservationComplete && !uncertain

        fun record(
            result: RefreshResult,
            unknown: Map<Long, ProcessHandle>,
            rootSignalSent: Boolean = false,
        ) {
            // Once an owned root signal has been sent, its expected exit can race
            // the next refresh. Preserve the complete pre-signal observation;
            // actual enumeration/capture failures remain sticky below.
            if (result == RefreshResult.ROOT_NOT_OWNED && rootSignalSent) return
            finalObservationComplete = false
            if (result != RefreshResult.SUCCESS || unknown.isNotEmpty()) {
                uncertain = true
                complete = false
                return
            }
            if (!uncertain) {
                complete = true
                finalObservationComplete = true
            }
        }
    }

    private data class TrackedProcess(val handle: ProcessHandle, val identity: ProcessIdentity)

    private data class WaitResult(
        val completed: Boolean,
        val interrupted: Boolean,
        val rootNotOwned: Boolean,
    )

    private companion object {
        const val POLL_MILLIS = 10L
        const val FORCE_WAIT_NANOS = 2_000_000_000L
        const val ROOT_DEATH_OBSERVE_POLLS = 100
        const val LAUNCH_CLEANUP_MILLIS = 2_000L
    }
}
