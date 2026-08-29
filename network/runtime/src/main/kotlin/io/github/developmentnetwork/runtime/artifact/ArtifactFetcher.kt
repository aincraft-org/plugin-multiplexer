package io.github.developmentnetwork.runtime.artifact

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID

/** Streams pinned artifacts to a verified, atomically installed destination. */
class ArtifactFetcher(private val http: HttpClient) {
    constructor() : this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build())
    fun fetch(url: URI, expectedSha256: String, destination: Path): Path {
        require(LOWERCASE_SHA256.matches(expectedSha256)) {
            "Expected SHA-256 must be exactly 64 lowercase hexadecimal characters"
        }
        return withDestinationLock(destination) {
            ensureSafeDestination(destination)
            Files.createDirectories(destination.parent ?: Path.of("."))
            if (Files.exists(destination, NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(destination, NOFOLLOW_LINKS)) {
                    "Artifact destination is not a regular file: $destination"
                }
                val actual = sha256(destination)
                if (actual == expectedSha256) return@withDestinationLock destination
                // Keep a stale-but-readable destination until the replacement has
                // been downloaded and verified.  A failed fetch must not destroy it.
            }
            val temporary = temporaryPath(destination)
            try {
                val actual = downloadTo(url, temporary)
                if (actual != expectedSha256) {
                    throw IOException("SHA-256 mismatch for $url: expected $expectedSha256, got $actual")
                }
                ensureSafeDestination(destination)
                atomicReplace(temporary, destination)
                destination
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    /** Download an unpinned selection while returning its computed lowercase SHA-256. */
    fun download(url: URI, destination: Path): String = withDestinationLock(destination) {
        ensureSafeDestination(destination)
        Files.createDirectories(destination.parent ?: Path.of("."))
        val temporary = temporaryPath(destination)
        try {
            val actual = downloadTo(url, temporary)
            ensureSafeDestination(destination)
            atomicReplace(temporary, destination)
            actual
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun downloadTo(url: URI, temporary: Path): String {
        val request = HttpRequest.newBuilder(url).GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted downloading $url", error)
        }
        if (response.statusCode() !in 200..299) {
            response.body().use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
            throw IOException("Artifact download failed with HTTP ${response.statusCode()} for $url")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            Files.newOutputStream(temporary, CREATE_NEW, WRITE).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ensureSafeDestination(destination: Path) {
        require(!Files.isSymbolicLink(destination)) {
            "Artifact destination must not be a symbolic link: $destination"
        }
    }

    private fun atomicReplace(source: Path, target: Path) {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun temporaryPath(destination: Path): Path {
        val name = destination.fileName?.toString()?.ifEmpty { "artifact" } ?: "artifact"
        return destination.resolveSibling(".$name-${UUID.randomUUID()}.tmp")
    }

    private fun <T> withDestinationLock(destination: Path, action: () -> T): T {
        val lock = destination.resolveSibling(".${destination.fileName ?: "artifact"}.lock")
        Files.createDirectories(lock.parent ?: Path.of("."))
        FileChannel.open(lock, CREATE, WRITE).use { channel ->
            while (true) {
                try {
                    channel.lock().use { return action() }
                } catch (_: OverlappingFileLockException) {
                    try {
                        Thread.sleep(10)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Interrupted while waiting for artifact lock $lock", error)
                    }
                }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
    }
}
