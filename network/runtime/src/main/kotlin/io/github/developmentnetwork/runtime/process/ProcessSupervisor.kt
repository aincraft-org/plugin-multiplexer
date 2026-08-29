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
        if (!process.process.isAlive) return TerminationResult.ALREADY_EXITED
        if (!safeMatches(process.identity)) return TerminationResult.NOT_OWNED
        if (wasInterrupted()) return TerminationResult.INTERRUPTED

        val root = process.process.toHandle()
        val tracked = LinkedHashMap<Long, TrackedProcess>()
        val unknown = LinkedHashMap<Long, ProcessHandle>()

        val initialRefresh = refreshDescendants(root, process.identity, tracked, unknown)
        when (initialRefresh) {
            RefreshResult.ROOT_NOT_OWNED -> return TerminationResult.NOT_OWNED
            RefreshResult.INTERRUPTED -> return TerminationResult.INTERRUPTED
            RefreshResult.INDETERMINATE,
            RefreshResult.SUCCESS,
            -> Unit
        }
        val initialDiscovery = initialRefresh == RefreshResult.SUCCESS && unknown.isEmpty()

        when (signalGracefully(root, process.identity, tracked)) {
            SignalResult.INTERRUPTED -> return TerminationResult.INTERRUPTED
            SignalResult.ROOT_NOT_OWNED -> {
                return when (observeTreeDeath(root, tracked, unknown)) {
                    Observation.DEAD -> TerminationResult.GRACEFUL
                    Observation.INTERRUPTED -> TerminationResult.INTERRUPTED
                    Observation.NOT_DEAD -> TerminationResult.NOT_OWNED
                }
            }
            SignalResult.COMPLETE -> Unit
        }
        val gracefulDeadline = deadlineNanos(timeout)
        val gracefulWait = waitForTree(
            root,
            process.identity,
            tracked,
            unknown,
            gracefulDeadline,
            initialDiscovery = initialDiscovery,
            forcePhase = false,
        )
        if (gracefulWait.interrupted) return TerminationResult.INTERRUPTED
        if (gracefulWait.completed) return TerminationResult.GRACEFUL
        if (gracefulWait.rootNotOwned) return TerminationResult.NOT_OWNED

        // Never let a graceful interruption trigger force escalation. This check
        // deliberately occurs before any force refresh or signal.
        if (wasInterrupted()) return TerminationResult.INTERRUPTED
        val forceRefresh = refreshDescendants(root, process.identity, tracked, unknown)
        when (forceRefresh) {
            RefreshResult.ROOT_NOT_OWNED -> return TerminationResult.NOT_OWNED
            RefreshResult.INTERRUPTED -> return TerminationResult.INTERRUPTED
            RefreshResult.INDETERMINATE,
            RefreshResult.SUCCESS,
            -> Unit
        }
        val forceDiscovery = forceRefresh == RefreshResult.SUCCESS && unknown.isEmpty()
        if (wasInterrupted()) return TerminationResult.INTERRUPTED

        when (signalForcibly(root, process.identity, tracked)) {
            SignalResult.INTERRUPTED -> return TerminationResult.INTERRUPTED
            SignalResult.ROOT_NOT_OWNED -> {
                return when (observeTreeDeath(root, tracked, unknown)) {
                    Observation.DEAD -> TerminationResult.FORCED
                    Observation.INTERRUPTED -> TerminationResult.INTERRUPTED
                    Observation.NOT_DEAD -> {
                        if (!safeAlive(root)) TerminationResult.NOT_TERMINATED else TerminationResult.NOT_OWNED
                    }
                }
            }
            SignalResult.COMPLETE -> Unit
        }
        val forceDeadline = safeAdd(System.nanoTime(), FORCE_WAIT_NANOS)
        val forceWait = waitForTree(
            root,
            process.identity,
            tracked,
            unknown,
            forceDeadline,
            initialDiscovery = forceDiscovery,
            forcePhase = true,
        )
        if (forceWait.interrupted) return TerminationResult.INTERRUPTED
        if (forceWait.completed) return TerminationResult.FORCED
        if (forceWait.rootNotOwned) {
            return if (!safeAlive(root) && !treeIsDead(root, tracked, unknown)) {
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
            if (wasInterrupted()) return SignalResult.INTERRUPTED
            if (!rootOwned(root, rootIdentity)) return SignalResult.ROOT_NOT_OWNED
            if (safeAlive(trackedProcess.handle) && safeMatches(trackedProcess.handle, trackedProcess.identity)) {
                trackedProcess.handle.destroy()
            }
        }
        if (wasInterrupted()) return SignalResult.INTERRUPTED
        if (!rootOwned(root, rootIdentity)) return SignalResult.ROOT_NOT_OWNED
        if (safeAlive(root) && safeMatches(root, rootIdentity)) root.destroy()
        return SignalResult.COMPLETE
    }

    private fun signalForcibly(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: Map<Long, TrackedProcess>,
    ): SignalResult {
        for (trackedProcess in tracked.values.toList().asReversed()) {
            if (wasInterrupted()) return SignalResult.INTERRUPTED
            if (!rootOwned(root, rootIdentity)) return SignalResult.ROOT_NOT_OWNED
            if (safeAlive(trackedProcess.handle) && safeMatches(trackedProcess.handle, trackedProcess.identity)) {
                trackedProcess.handle.destroyForcibly()
            }
        }
        if (wasInterrupted()) return SignalResult.INTERRUPTED
        if (!rootOwned(root, rootIdentity)) return SignalResult.ROOT_NOT_OWNED
        if (safeAlive(root) && safeMatches(root, rootIdentity)) root.destroyForcibly()
        return SignalResult.COMPLETE
    }

    private fun waitForTree(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: MutableMap<Long, TrackedProcess>,
        unknown: MutableMap<Long, ProcessHandle>,
        deadline: Long,
        initialDiscovery: Boolean,
        forcePhase: Boolean,
    ): WaitResult {
        var discoverySucceeded = initialDiscovery
        while (true) {
            if (wasInterrupted()) return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
            if (treeIsDead(root, tracked, unknown) && discoverySucceeded) {
                return WaitResult(completed = true, interrupted = false, rootNotOwned = false)
            }
            // A dead or mismatched root is never a reason to continue discovering or
            // signalling descendants. It is safe to report completion only when the
            // entire already-verified tree is dead.
            if (!rootOwned(root, rootIdentity)) {
                return WaitResult(completed = false, interrupted = false, rootNotOwned = true)
            }
            when (val refreshed = refreshDescendants(root, rootIdentity, tracked, unknown)) {
                RefreshResult.ROOT_NOT_OWNED -> {
                    return if (treeIsDead(root, tracked, unknown) && discoverySucceeded) {
                        WaitResult(completed = true, interrupted = false, rootNotOwned = false)
                    } else {
                        WaitResult(completed = false, interrupted = false, rootNotOwned = true)
                    }
                }
                RefreshResult.INTERRUPTED -> {
                    return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
                }
                RefreshResult.SUCCESS -> discoverySucceeded = unknown.isEmpty()
                RefreshResult.INDETERMINATE -> discoverySucceeded = false
            }
            if (wasInterrupted()) return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
            if (treeIsDead(root, tracked, unknown) && discoverySucceeded) {
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
            when (signal) {
                SignalResult.INTERRUPTED -> {
                    return WaitResult(completed = false, interrupted = true, rootNotOwned = false)
                }
                SignalResult.ROOT_NOT_OWNED -> {
                    return if (treeIsDead(root, tracked, unknown) && discoverySucceeded) {
                        WaitResult(completed = true, interrupted = false, rootNotOwned = false)
                    } else {
                        WaitResult(completed = false, interrupted = false, rootNotOwned = true)
                    }
                }
                SignalResult.COMPLETE -> Unit
            }
            if (treeIsDead(root, tracked, unknown) && discoverySucceeded) {
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

    /** Return true only when every known and indeterminate identity is observably dead. */
    private fun treeIsDead(
        root: ProcessHandle,
        tracked: Map<Long, TrackedProcess>,
        unknown: Map<Long, ProcessHandle>,
    ): Boolean {
        if (safeAlive(root)) return false
        if (unknown.values.any(::safeAlive)) return false
        return tracked.values.all { trackedProcess ->
            when (observedState(trackedProcess)) {
                ObservedState.DEAD -> true
                ObservedState.OWNED_ALIVE, ObservedState.UNKNOWN -> false
            }
        }
    }

    private fun observedState(tracked: TrackedProcess): ObservedState {
        val current = runCatching { ProcessHandle.of(tracked.identity.pid).orElse(null) }.getOrNull()
            ?: return ObservedState.DEAD
        if (!safeAlive(current)) return ObservedState.DEAD
        val expectedStart = tracked.identity.startInstant ?: return ObservedState.UNKNOWN
        // A different start instant proves the tracked lease exited and the PID was
        // reused; do not mistake the replacement for an owned descendant.
        val actualStart = runCatching { current.info().startInstant().orElse(null) }.getOrNull()
        if (actualStart != expectedStart) return ObservedState.UNKNOWN
        return if (safeMatches(current, tracked.identity)) {
            ObservedState.OWNED_ALIVE
        } else {
            ObservedState.UNKNOWN
        }
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

        for (handle in handles) {
            if (wasInterrupted()) return RefreshResult.INTERRUPTED
            if (!rootOwned(root, rootIdentity)) return RefreshResult.ROOT_NOT_OWNED
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
        // represents a live unverified descendant. Any other failure remains gated.
        unknown.entries.removeIf { !safeAlive(it.value) }
        return RefreshResult.SUCCESS
    }

    private fun observeTreeDeath(
        root: ProcessHandle,
        tracked: Map<Long, TrackedProcess>,
        unknown: Map<Long, ProcessHandle>,
    ): Observation {
        repeat(ROOT_DEATH_OBSERVE_POLLS) {
            if (wasInterrupted()) return Observation.INTERRUPTED
            if (treeIsDead(root, tracked, unknown)) return Observation.DEAD
            if (safeAlive(root)) return Observation.NOT_DEAD
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Observation.INTERRUPTED
            }
        }
        return if (treeIsDead(root, tracked, unknown)) Observation.DEAD else Observation.NOT_DEAD
    }

    private fun rootOwned(root: ProcessHandle, identity: ProcessIdentity): Boolean =
        safeAlive(root) && safeMatches(root, identity)

    private fun safeMatches(identity: ProcessIdentity): Boolean =
        runCatching { identityReader.matches(identity) }.getOrDefault(false)

    private fun safeMatches(handle: ProcessHandle, identity: ProcessIdentity): Boolean =
        runCatching { identityReader.matches(handle, identity) }.getOrDefault(false)

    private fun safeAlive(handle: ProcessHandle): Boolean =
        runCatching { handle.isAlive }.getOrDefault(true)

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
    private enum class SignalResult { COMPLETE, ROOT_NOT_OWNED, INTERRUPTED }
    private enum class Observation { DEAD, NOT_DEAD, INTERRUPTED }

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
