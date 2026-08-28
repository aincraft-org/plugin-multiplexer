package io.github.developmentnetwork.runtime.state

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** Small, same-directory atomic replacement primitive for runtime state. */
object AtomicFiles {
    fun write(path: Path, content: String) {
        writeBytes(path, content.toByteArray(StandardCharsets.UTF_8), ::atomicMove)
    }

    /** Test seam used to exercise failure-safe handling of unsupported atomic moves. */
    internal fun write(path: Path, content: String, move: (Path, Path) -> Unit) {
        writeBytes(path, content.toByteArray(StandardCharsets.UTF_8), move)
    }

    fun writeLines(path: Path, lines: Iterable<String>) {
        val values = lines.toList()
        val content = if (values.isEmpty()) "" else values.joinToString("\n", postfix = "\n")
        write(path, content)
    }

    fun read(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)

    fun readIfExists(path: Path): String? =
        if (Files.exists(path)) read(path) else null

    fun readLines(path: Path): List<String> =
        Files.readAllLines(path, StandardCharsets.UTF_8)

    fun readLinesIfExists(path: Path): List<String>? =
        if (Files.exists(path)) readLines(path) else null

    private fun writeBytes(path: Path, bytes: ByteArray, move: (Path, Path) -> Unit) {
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val fileName = path.fileName?.toString().orEmpty().ifEmpty { "state" }
        val temporary = Files.createTempFile(parent, ".${fileName}-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                }
                channel.force(true)
            }
            move(temporary, path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
