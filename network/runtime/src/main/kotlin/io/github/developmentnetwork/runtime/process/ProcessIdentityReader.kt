package io.github.developmentnetwork.runtime.process

import io.github.developmentnetwork.runtime.model.ProcessIdentity
import java.nio.file.Files
import java.nio.file.Path

/** Captures and verifies the identity of one OS process lease. */
class ProcessIdentityReader {
    /** Capture stable process metadata before a PID can be reused. */
    fun capture(process: Process, cwd: Path): ProcessIdentity {
        val handle = process.toHandle()
        return capture(handle, cwd)
    }

    /** Return true only when the PID still denotes the same live process lease. */
    fun matches(identity: ProcessIdentity): Boolean {
        val handle = ProcessHandle.of(identity.pid).orElse(null) ?: return false
        return matches(handle, identity)
    }

    internal fun capture(handle: ProcessHandle, cwd: Path): ProcessIdentity {
        val info = handle.info()
        val executable = info.command().orElse(null)?.let(::normalize)
        val start = info.startInstant().orElse(null)
        return ProcessIdentity(
            pid = handle.pid(),
            startInstant = start,
            executable = executable,
            workingDirectory = normalize(cwd),
        )
    }

    internal fun captureDescendant(handle: ProcessHandle, fallbackCwd: Path): ProcessIdentity {
        val identity = capture(handle, fallbackCwd)
        return identity.copy(workingDirectory = processWorkingDirectory(handle) ?: identity.workingDirectory)
    }

    internal fun matches(handle: ProcessHandle, identity: ProcessIdentity): Boolean {
        if (!handle.isAlive || handle.pid() != identity.pid) return false

        val info = handle.info()
        // A missing start identity cannot safely distinguish a reused PID. Fail closed.
        val expectedStart = identity.startInstant ?: return false
        if (info.startInstant().orElse(null) != expectedStart) return false

        identity.executable?.let { expected ->
            val actual = info.command().orElse(null)?.let(::normalize) ?: return false
            if (actual != expected) return false
        }
        // Java's ProcessHandle.Info has no portable working-directory field. On Linux,
        // verify it from /proc when available; otherwise the persisted expected path is
        // retained as an additional lease attribute without inventing a platform-specific
        // process lookup.
        identity.workingDirectory?.let { expected ->
            if (!Files.isDirectory(expected)) return false
            processWorkingDirectory(handle)?.let { actual ->
                if (actual != normalize(expected)) return false
            }
        }
        return true
    }

    private fun normalize(path: String): Path = normalize(Path.of(path))

    private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
    private fun processWorkingDirectory(handle: ProcessHandle): Path? {
        val procCwd = Path.of("/proc", handle.pid().toString(), "cwd")
        return runCatching {
            if (Files.isSymbolicLink(procCwd)) normalize(Files.readSymbolicLink(procCwd)) else null
        }.getOrNull()
    }
}
