package io.github.developmentnetwork.runtime.process

import io.github.developmentnetwork.runtime.model.ProcessIdentity
import java.io.OutputStream
import java.time.Duration

/** A process and the identity captured at the instant this runtime started owning it. */
data class OwnedProcess(
    val process: Process,
    val identity: ProcessIdentity,
    /** The child stdin channel; its lifetime is owned by the caller of [launch]. */
    val stdin: OutputStream?,
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
        NOT_OWNED(false, false, false),
        ;

        val graceful: Boolean get() = this == GRACEFUL
        val forced: Boolean get() = forceEscalated
        val success: Boolean get() = terminated
    }
    fun launch(command: List<String>, cwd: java.nio.file.Path, stdin: OutputStream? = null): OwnedProcess {
        require(command.isNotEmpty()) { "Process command must not be empty" }
        require(java.nio.file.Files.isDirectory(cwd)) { "Process working directory does not exist: $cwd" }

        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .start()
        return OwnedProcess(
            process = process,
            identity = identityReader.capture(process, cwd),
            stdin = stdin ?: process.outputStream,
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
     * Gracefully terminate the owned process tree, then force matching identities after
     * [timeout]. A changed/reused PID is never signalled.
     */
    fun terminate(process: OwnedProcess, timeout: Duration): TerminationResult {
        require(!timeout.isNegative) { "Termination timeout must not be negative" }
        if (!process.process.isAlive) return TerminationResult.ALREADY_EXITED
        if (!identityReader.matches(process.identity)) return TerminationResult.NOT_OWNED

        val root = process.process.toHandle()
        val descendants = root.descendants()
            .map { handle ->
                TrackedProcess(
                    handle,
                    identityReader.captureDescendant(
                        handle,
                        process.identity.workingDirectory ?: java.nio.file.Path.of("."),
                    ),
                )
            }
            .toList()

        // Signal children before the parent so the parent cannot orphan them while it exits.
        descendants.asReversed().forEach { tracked -> destroyGracefully(tracked) }
        destroyGracefully(TrackedProcess(root, process.identity))

        val deadline = deadlineNanos(timeout)
        if (waitForTree(root, descendants, deadline)) return TerminationResult.GRACEFUL

        var forceAttempted = false
        // Re-check every identity immediately before escalation. This is the PID-reuse gate.
        descendants.asReversed().forEach { tracked ->
            if (tracked.handle.isAlive && identityReader.matches(tracked.handle, tracked.identity)) {
                forceAttempted = true
                tracked.handle.destroyForcibly()
            }
        }
        if (root.isAlive && identityReader.matches(root, process.identity)) {
            forceAttempted = true
            root.destroyForcibly()
        }
        if (!root.isAlive && descendants.none { it.handle.isAlive }) {
            return TerminationResult.GRACEFUL
        }
        if (!forceAttempted) return TerminationResult.NOT_OWNED

        // Force escalation itself is bounded; do not let a broken process block controller exit.
        val forceDeadline = minOf(deadlineNanos(Duration.ofSeconds(2)), System.nanoTime() + FORCE_WAIT_NANOS)
        waitForTree(root, descendants, forceDeadline)
        return TerminationResult.FORCED
    }

    private fun destroyGracefully(tracked: TrackedProcess) {
        if (tracked.handle.isAlive && identityReader.matches(tracked.handle, tracked.identity)) {
            tracked.handle.destroy()
        }
    }

    private fun waitForTree(root: ProcessHandle, descendants: List<TrackedProcess>, deadline: Long): Boolean {
        while (root.isAlive || descendants.any { it.handle.isAlive }) {
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
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
        val now = System.nanoTime()
        return if (nanos >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + nanos
    }

    private data class TrackedProcess(val handle: ProcessHandle, val identity: ProcessIdentity)

    private companion object {
        const val POLL_MILLIS = 10L
        const val FORCE_WAIT_NANOS = 2_000_000_000L
    }
}
