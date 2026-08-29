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
        return OwnedProcess(
            process = process,
            identity = identityReader.capture(process, cwd),
            stdin = process.outputStream,
        )
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
        if (!identityReader.matches(process.identity)) return TerminationResult.NOT_OWNED

        val root = process.process.toHandle()
        val tracked = LinkedHashMap<Long, TrackedProcess>()
        val discovery = refreshDescendants(root, process.identity, tracked)
        signalGracefully(tracked)
        if (root.isAlive && identityReader.matches(root, process.identity)) root.destroy()

        val gracefulDeadline = deadlineNanos(timeout)
        val gracefulWait = waitForTree(root, tracked, process.identity, gracefulDeadline, discovery, forcePhase = false)
        if (gracefulWait.interrupted) return TerminationResult.INTERRUPTED
        if (gracefulWait.completed) return TerminationResult.GRACEFUL

        // Reaching this branch means the graceful deadline expired, rather than that
        // this caller was interrupted. Refresh before every force signal and wait poll.
        var forceAttempted = false
        var forceDiscovery = refreshDescendants(root, process.identity, tracked)
        forceAttempted = signalForcibly(tracked) || forceAttempted
        if (root.isAlive) {
            if (identityReader.matches(root, process.identity)) {
                forceAttempted = true
                root.destroyForcibly()
            } else {
                forceDiscovery = false
            }
        }

        val forceDeadline = safeAdd(System.nanoTime(), FORCE_WAIT_NANOS)
        val forceWait = waitForTree(root, tracked, process.identity, forceDeadline, forceDiscovery, forcePhase = true)
        if (forceWait.interrupted) return TerminationResult.INTERRUPTED
        if (forceWait.completed) return TerminationResult.FORCED
        if (!forceAttempted && !identityReader.matches(process.identity)) return TerminationResult.NOT_OWNED

        // A force request is not evidence of termination. Report failure until the
        // root and every discovered owned descendant have actually been observed dead.
        return TerminationResult.NOT_TERMINATED
    }

    private fun signalGracefully(tracked: Map<Long, TrackedProcess>) {
        tracked.values.toList().asReversed().forEach { trackedProcess ->
            if (trackedProcess.handle.isAlive && identityReader.matches(trackedProcess.handle, trackedProcess.identity)) {
                trackedProcess.handle.destroy()
            }
        }
    }

    private fun signalForcibly(tracked: Map<Long, TrackedProcess>): Boolean {
        var attempted = false
        tracked.values.toList().asReversed().forEach { trackedProcess ->
            if (trackedProcess.handle.isAlive && identityReader.matches(trackedProcess.handle, trackedProcess.identity)) {
                attempted = true
                trackedProcess.handle.destroyForcibly()
            }
        }
        return attempted
    }

    private fun waitForTree(
        root: ProcessHandle,
        tracked: MutableMap<Long, TrackedProcess>,
        rootIdentity: ProcessIdentity,
        deadline: Long,
        initialDiscovery: Boolean,
        forcePhase: Boolean,
    ): WaitResult {
        var discoverySucceeded = initialDiscovery
        while (true) {
            val refreshed = refreshDescendants(root, rootIdentity, tracked)
            discoverySucceeded = discoverySucceeded && refreshed
            if (forcePhase) signalForcibly(tracked) else signalGracefully(tracked)
            if (treeIsDead(root, tracked) && discoverySucceeded) return WaitResult(completed = true, interrupted = false)
            if (System.nanoTime() >= deadline) return WaitResult(completed = false, interrupted = false)
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                return WaitResult(completed = false, interrupted = true)
            }
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return WaitResult(completed = false, interrupted = true)
            }
        }
    }

    /** Return true only when every known identity is observably dead. */
    private fun treeIsDead(root: ProcessHandle, tracked: Map<Long, TrackedProcess>): Boolean {
        if (root.isAlive) return false
        return tracked.values.all { trackedProcess ->
            when (observedState(trackedProcess)) {
                ObservedState.DEAD -> true
                ObservedState.OWNED_ALIVE, ObservedState.UNKNOWN -> false
            }
        }
    }

    private fun observedState(tracked: TrackedProcess): ObservedState {
        val current = ProcessHandle.of(tracked.identity.pid).orElse(null) ?: return ObservedState.DEAD
        if (!current.isAlive) return ObservedState.DEAD
        val expectedStart = tracked.identity.startInstant ?: return ObservedState.UNKNOWN
        // A different start instant proves the tracked lease exited and the PID was
        // reused; do not mistake the replacement for an owned descendant.
        if (current.info().startInstant().orElse(null) != expectedStart) return ObservedState.DEAD
        return if (identityReader.matches(current, tracked.identity)) {
            ObservedState.OWNED_ALIVE
        } else {
            ObservedState.UNKNOWN
        }
    }

    /** Refresh descendants and replace only a PID's changed process lease. */
    private fun refreshDescendants(
        root: ProcessHandle,
        rootIdentity: ProcessIdentity,
        tracked: MutableMap<Long, TrackedProcess>,
    ): Boolean {
        val handles = runCatching { root.descendants().toList() }.getOrElse { return false }
        handles.forEach { handle ->
            val identity = runCatching {
                identityReader.captureDescendant(handle, rootIdentity.workingDirectory ?: java.nio.file.Path.of("."))
            }.getOrNull() ?: return@forEach
            val previous = tracked[identity.pid]
            if (previous == null || previous.identity.startInstant != identity.startInstant) {
                tracked[identity.pid] = TrackedProcess(handle, identity)
            }
        }
        return true
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

    private data class TrackedProcess(val handle: ProcessHandle, val identity: ProcessIdentity)

    private data class WaitResult(val completed: Boolean, val interrupted: Boolean)

    private companion object {
        const val POLL_MILLIS = 10L
        const val FORCE_WAIT_NANOS = 2_000_000_000L
    }
}
