package io.github.developmentnetwork.runtime.state

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Blocking locks for infrastructure, registry, and individual artifact operations. */
class FileLocks(private val layout: RuntimeLayout) {
    fun <T> withProxyLock(action: () -> T): T = withLock(layout.proxyLock, action)

    fun <T> withRegistrationLock(action: () -> T): T = withLock(layout.registrationLock, action)

    /** Lock adjacent to an artifact, so unrelated artifacts can proceed concurrently. */
    fun <T> withArtifactLock(artifact: Path, action: () -> T): T =
        withLock(artifactLock(artifact), action)

    fun <T> withArtifactLock(artifact: String, action: () -> T): T =
        withArtifactLock(Path.of(artifact), action)

    fun artifactLock(artifact: Path): Path {
        val fileName = artifact.fileName?.toString()?.ifEmpty { "artifact" } ?: "artifact"
        return artifact.resolveSibling(".$fileName.lock")
    }

    private fun <T> withLock(path: Path, action: () -> T): T {
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        FileChannel.open(path, CREATE, WRITE).use { channel ->
            acquire(channel, path).use {
                return action()
            }
        }
    }

    private fun acquire(channel: FileChannel, path: Path): FileLock {
        while (true) {
            try {
                return channel.lock()
            } catch (_: OverlappingFileLockException) {
                try {
                    Thread.sleep(10)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for lock $path", error)
                }
            }
        }
    }
}
