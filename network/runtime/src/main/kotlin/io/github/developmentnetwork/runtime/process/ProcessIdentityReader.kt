package io.github.developmentnetwork.runtime.process

import io.github.developmentnetwork.runtime.model.ProcessIdentity
import java.nio.file.Files
import java.nio.file.Path

/** Captures and verifies the identity of one OS process lease. */
open class ProcessIdentityReader {
    /** Capture stable process metadata before a PID can be reused. */
    fun capture(process: Process, cwd: Path): ProcessIdentity {
        return capture(process.toHandle(), cwd)
    }

    /** Return true only when the PID still denotes the same live process lease. */
    open fun matches(identity: ProcessIdentity): Boolean {
        val handle = ProcessHandle.of(identity.pid).orElse(null) ?: return false
        return matches(handle, identity)
    }

    internal fun capture(handle: ProcessHandle, cwd: Path): ProcessIdentity {
        val info = handle.info()
        val executable = info.command().orElse(null)?.let(::canonicalize)
        val start = info.startInstant().orElse(null)
        return ProcessIdentity(
            pid = handle.pid(),
            startInstant = start,
            executable = executable,
            workingDirectory = canonicalize(cwd),
        )
    }

    internal fun captureDescendant(handle: ProcessHandle, fallbackCwd: Path): ProcessIdentity {
        val identity = capture(handle, fallbackCwd)
        return identity.copy(workingDirectory = processWorkingDirectory(handle) ?: identity.workingDirectory)
    }

    internal open fun matches(handle: ProcessHandle, identity: ProcessIdentity): Boolean {
        if (!handle.isAlive || handle.pid() != identity.pid) return false

        val info = handle.info()
        // A missing start identity cannot safely distinguish a reused PID. Fail closed.
        val expectedStart = identity.startInstant ?: return false
        if (info.startInstant().orElse(null) != expectedStart) return false

        identity.executable?.let { expected ->
            val actual = info.command().orElse(null)?.let(::canonicalize) ?: return false
            if (actual != expected) return false
        }

        // Java's ProcessHandle.Info has no portable working-directory field. The
        // Linux /proc link is therefore required whenever a working directory is
        // part of the lease; if it cannot be read, verification fails closed.
        identity.workingDirectory?.let { expected ->
            val actual = processWorkingDirectory(handle) ?: return false
            if (actual != expected) return false
        }
        return true
    }

    private fun canonicalize(path: String): Path = canonicalize(Path.of(path))

    private fun canonicalize(path: Path): Path = path.toAbsolutePath().normalize().toRealPath()

    private fun processWorkingDirectory(handle: ProcessHandle): Path? {
        val procCwd = Path.of("/proc", handle.pid().toString(), "cwd")
        return runCatching {
            if (!Files.isSymbolicLink(procCwd)) return@runCatching null
            procCwd.toRealPath()
        }.getOrNull()
    }
}
