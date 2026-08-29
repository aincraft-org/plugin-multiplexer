package io.github.developmentnetwork

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Extracts and starts the runtime artifact embedded in the plugin JAR. */
object RuntimeArtifactLauncher {
    const val RESOURCE_PATH = "META-INF/development-network/runtime.jar"

    private const val CACHE_PATH = "caches/development-network/runtime"
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Extracts the embedded runtime into a content-addressed Gradle-user-home cache.
     *
     * A cache entry is reused only when both its size and SHA-256 match the embedded
     * resource. Extraction is serialized per digest so parallel Gradle workers cannot
     * observe an incomplete runtime JAR.
     */
    @JvmStatic
    fun extract(gradleUserHome: File, classLoader: ClassLoader): File {
        val digest = digestResource(classLoader)
        val cacheRoot = gradleUserHome.toPath().toAbsolutePath().normalize().resolve(CACHE_PATH)
        val cacheDir = cacheRoot.resolve(digest.hex)
        Files.createDirectories(cacheDir)

        val target = cacheDir.resolve("runtime.jar")
        val lockFile = cacheDir.resolve(".extract.lock")
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            lock(channel).use {
                if (matches(target, digest)) {
                    return target.toFile()
                }

                var temporary: Path? = null
                try {
                    temporary = Files.createTempFile(cacheDir, ".runtime-", ".tmp")
                    openResource(classLoader).use { input ->
                        copy(input, temporary)
                    }
                    check(matches(temporary, digest)) {
                        "Embedded runtime resource changed while extracting; refusing to install an unverified JAR"
                    }
                    moveAtomically(temporary, target)
                    temporary = null
                    return target.toFile()
                } finally {
                    temporary?.let { Files.deleteIfExists(it) }
                }
            }
        }
    }

    /** Starts the embedded runtime with inherited console I/O in [projectDir]. */
    @JvmStatic
    fun launch(projectDir: File, gradleUserHome: File, request: List<String>): Process {
        val runtimeJar = extract(gradleUserHome, RuntimeArtifactLauncher::class.java.classLoader)
        val javaExecutable = javaExecutable()
        if (!Files.isRegularFile(javaExecutable) || !Files.isExecutable(javaExecutable)) {
            throw IllegalStateException(
                "Java executable is missing or not executable: $javaExecutable " +
                    "(derived from java.home=${System.getProperty("java.home")})"
            )
        }
        if (!projectDir.isDirectory) {
            throw IllegalArgumentException("Runtime project directory does not exist: $projectDir")
        }

        val command = ArrayList<String>(request.size + 5)
        command += javaExecutable.toString()
        command += "--enable-preview"
        command += "--enable-native-access=ALL-UNNAMED"
        command += "-jar"
        command += runtimeJar.absolutePath
        command.addAll(request)
        return ProcessBuilder(command)
            .directory(projectDir)
            .inheritIO()
            .start()
    }

    private data class ResourceDigest(val hex: String, val size: Long)

    private fun digestResource(classLoader: ClassLoader): ResourceDigest {
        openResource(classLoader).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var size = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) break
                    digest.update(value.toByte())
                    size++
                } else {
                    digest.update(buffer, 0, count)
                    size += count
                }
            }
            return ResourceDigest(digest.digest().toHex(), size)
        }
    }

    private fun openResource(classLoader: ClassLoader): InputStream =
        classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: throw IllegalStateException(
                "Embedded runtime resource $RESOURCE_PATH was not found in the plugin JAR"
            )

    private fun matches(path: Path, expected: ResourceDigest): Boolean {
        if (!Files.isRegularFile(path)) return false
        return try {
            if (Files.size(path) != expected.size) return false
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val value = input.read()
                        if (value < 0) break
                        digest.update(value.toByte())
                    } else {
                        digest.update(buffer, 0, count)
                    }
                }
            }
            digest.digest().toHex() == expected.hex
        } catch (_: IOException) {
            false
        }
    }

    private fun copy(input: InputStream, destination: Path) {
        Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) break
                    output.write(value)
                } else {
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    internal fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException(
                "Cannot install embedded runtime atomically: filesystem does not support ATOMIC_MOVE " +
                    "($source -> $target)",
                unsupported
            )
        } catch (unsupported: UnsupportedOperationException) {
            throw IOException(
                "Cannot install embedded runtime atomically: filesystem rejected ATOMIC_MOVE " +
                    "($source -> $target)",
                unsupported
            )
        }
    }

    private fun lock(channel: FileChannel): FileLock {
        while (true) {
            try {
                return channel.lock()
            } catch (_: OverlappingFileLockException) {
                try {
                    Thread.sleep(10)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for runtime extraction lock", interrupted)
                }
            }
        }
    }

    private fun javaExecutable(): Path {
        val javaHome = System.getProperty("java.home")
            ?: throw IllegalStateException("java.home is not set; cannot launch the embedded runtime")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return Path.of(javaHome, "bin", executable)
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            append("0123456789abcdef"[(byte.toInt() ushr 4) and 0x0f])
            append("0123456789abcdef"[byte.toInt() and 0x0f])
        }
    }
}
