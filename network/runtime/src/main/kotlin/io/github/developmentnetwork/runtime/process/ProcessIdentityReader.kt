package io.github.developmentnetwork.runtime.process

import io.github.developmentnetwork.runtime.model.ProcessIdentity
import java.nio.file.Files
import java.nio.file.Path

/** Captures and verifies the identity of one OS process lease. */
open class ProcessIdentityReader {
    /** Capture stable process metadata before a PID can be reused. */
    open fun capture(process: Process, cwd: Path): ProcessIdentity {
        return capture(process.toHandle(), cwd)
    }

    /** Return true only when the PID still denotes the same live process lease. */
    open fun matches(identity: ProcessIdentity): Boolean {
        return runCatching {
            val handle = ProcessHandle.of(identity.pid).orElse(null) ?: return@runCatching false
            matches(handle, identity)
        }.getOrDefault(false)
    }

    internal fun capture(handle: ProcessHandle, cwd: Path): ProcessIdentity {
        val info = runCatching { handle.info() }.getOrNull()
        val executable = runCatching {
            info?.command()?.orElse(null)?.let(::canonicalize)
        }.getOrNull()
        val start = runCatching { info?.startInstant()?.orElse(null) }.getOrNull()
        return ProcessIdentity(
            pid = handle.pid(),
            startInstant = start,
            executable = executable,
            workingDirectory = canonicalizeOrNull(cwd),
        )
    }

    internal open fun captureDescendant(handle: ProcessHandle, fallbackCwd: Path): ProcessIdentity {
        val identity = capture(handle, fallbackCwd)
        val workingDirectory = runCatching { processWorkingDirectory(handle) }.getOrNull()
        return identity.copy(workingDirectory = workingDirectory)
    }

    internal open fun matches(handle: ProcessHandle, identity: ProcessIdentity): Boolean {
        return runCatching {
            if (!handle.isAlive || handle.pid() != identity.pid) return@runCatching false

            val info = handle.info()
            // A missing start identity cannot safely distinguish a reused PID. Fail closed.
            val expectedStart = identity.startInstant ?: return@runCatching false
            if (info.startInstant().orElse(null) != expectedStart) return@runCatching false

            val expectedExecutable = identity.executable ?: return@runCatching false
            val actualExecutable = info.command().orElse(null)?.let(::canonicalizeOrNull)
                ?: return@runCatching false
            if (actualExecutable != expectedExecutable) return@runCatching false

            // Java's ProcessHandle.Info has no portable working-directory field. The
            // Linux /proc link is therefore required whenever a working directory is
            // part of the lease; if it cannot be read, verification fails closed.
            val expectedWorkingDirectory = identity.workingDirectory ?: return@runCatching false
            val actualWorkingDirectory = processWorkingDirectory(handle)
                ?: return@runCatching false
            actualWorkingDirectory == expectedWorkingDirectory
        }.getOrDefault(false)
    }

    private fun canonicalize(path: String): Path = canonicalize(Path.of(path))

    private fun canonicalize(path: Path): Path = path.toAbsolutePath().normalize().toRealPath()

    private fun canonicalizeOrNull(path: String): Path? =
        runCatching { canonicalize(path) }.getOrNull()

    private fun canonicalizeOrNull(path: Path): Path? =
        runCatching { canonicalize(path) }.getOrNull()

    private fun processWorkingDirectory(handle: ProcessHandle): Path? {
        val procCwd = Path.of("/proc", handle.pid().toString(), "cwd")
        return runCatching {
            if (!Files.isSymbolicLink(procCwd)) return@runCatching null
            procCwd.toRealPath()
        }.getOrNull()
    }
}

