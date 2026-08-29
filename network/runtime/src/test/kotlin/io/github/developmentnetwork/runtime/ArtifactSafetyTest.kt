package io.github.developmentnetwork.runtime

import com.sun.net.httpserver.HttpServer
import io.github.developmentnetwork.runtime.artifact.ArtifactFetcher
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArtifactSafetyTest {
    @Test
    fun existingFileWithMatchingChecksumIsReusedAndMismatchReplacementPreservesOldUntilVerified() {
        val directory = Files.createTempDirectory("artifact")
        val destination = directory.resolve("paper.jar")
        val bytes = "fresh artifact".toByteArray()
        val checksum = sha256(bytes)
        Files.writeString(destination, "stale")
        val server = fixture(bytes)
        try {
            val result = ArtifactFetcher().fetch(URI("http://127.0.0.1:${server.address.port}/artifact"), checksum, destination)
            assertEquals(destination, result)
            assertEquals("fresh artifact", Files.readString(destination))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun matchingExistingFileIsReusedWithoutARequest() {
        val directory = Files.createTempDirectory("artifact-reuse")
        val destination = directory.resolve("paper.jar")
        val bytes = "already verified".toByteArray()
        val checksum = sha256(bytes)
        Files.write(destination, bytes)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/artifact") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            ArtifactFetcher().fetch(URI("http://127.0.0.1:${server.address.port}/artifact"), checksum, destination)
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun checksumFailurePreservesExistingDestinationAndCleansTemporaryOutput() {
        val directory = Files.createTempDirectory("artifact-failure")
        val destination = directory.resolve("paper.jar")
        Files.writeString(destination, "old verified artifact")
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/artifact") { exchange ->
                exchange.sendResponseHeaders(200, 5)
                exchange.responseBody.use { it.write("short".toByteArray()) }
            }
            start()
        }
        try {
            assertFailsWith<Exception> {
                ArtifactFetcher().fetch(
                    URI("http://127.0.0.1:${server.address.port}/artifact"),
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    destination,
                )
            }
            assertEquals("old verified artifact", Files.readString(destination))
            assertTrue(Files.list(directory).use { stream -> stream.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun httpFailurePreservesExistingDestination() {
        val directory = Files.createTempDirectory("artifact-http-failure")
        val destination = directory.resolve("paper.jar")
        Files.writeString(destination, "old verified artifact")
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/artifact") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        try {
            assertFailsWith<Exception> {
                ArtifactFetcher().fetch(
                    URI("http://127.0.0.1:${server.address.port}/artifact"),
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    destination,
                )
            }
            assertEquals("old verified artifact", Files.readString(destination))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun symlinkDestinationIsRejectedWithoutFollowingTarget() {
        val directory = Files.createTempDirectory("artifact-symlink")
        val target = directory.resolve("target.jar")
        Files.writeString(target, "old target")
        val destination = directory.resolve("paper.jar")
        Files.createSymbolicLink(destination, target.fileName)
        val bytes = "fresh artifact".toByteArray()
        val server = fixture(bytes)
        try {
            assertFailsWith<IllegalArgumentException> {
                ArtifactFetcher().fetch(
                    URI("http://127.0.0.1:${server.address.port}/artifact"),
                    sha256(bytes),
                    destination,
                )
            }
            assertEquals("old target", Files.readString(target))
            assertTrue(Files.isSymbolicLink(destination))
        } finally {
            server.stop(0)
        }
    }

    private fun fixture(bytes: ByteArray): HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/artifact") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
