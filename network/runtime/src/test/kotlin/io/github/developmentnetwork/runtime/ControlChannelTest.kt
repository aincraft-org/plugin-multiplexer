package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlResponse
import io.github.developmentnetwork.runtime.controller.ControlServer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ControlChannelTest {
    @Test
    fun authenticatedReloadAndShutdownUseRandomOwnerOnlyState() {
        val runtime = Files.createTempDirectory("control-channel").resolve("runtime")
        Files.createDirectories(runtime)
        val socket = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        val server = ControlServer().serve(socket, token) { command ->
            when (command) {
                ControlCommand.Reload -> ControlResponse(true, "reloaded")
                ControlCommand.Shutdown -> ControlResponse(true, "shutdown")
            }
        }
        try {
            assertTrue(Files.exists(socket))
            assertTrue(Files.exists(ControlServer.tokenPath(socket)))
            assertTrue(Files.exists(ControlServer.leasePath(socket)))
            assertOwnerOnly(runtime)
            assertOwnerOnly(socket)
            assertOwnerOnly(ControlServer.tokenPath(socket))
            assertOwnerOnly(ControlServer.leasePath(socket))
            assertEquals("reloaded", ControlClient().request(
                socket, token, ControlCommand.Reload, Duration.ofSeconds(2),
            ).message)
            assertEquals("shutdown", ControlClient().request(
                socket, token, ControlCommand.Shutdown, Duration.ofSeconds(2),
            ).message)
        } finally {
            server.close()
        }
        assertFalse(Files.exists(socket))
        assertFalse(Files.exists(ControlServer.tokenPath(socket)))
    }

    @Test
    fun wrongTokenIsRejectedBeforeHandlerRuns() {
        val runtime = Files.createTempDirectory("control-channel-auth")
        val socket = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        var calls = 0
        val server = ControlServer().serve(socket, token) {
            calls += 1
            ControlResponse(true, "unexpected")
        }
        try {
            val response = ControlClient().request(
                socket, "not-$token", ControlCommand.Reload, Duration.ofSeconds(2),
            )
            assertFalse(response.ok)
            assertEquals("authentication failed", response.message)
            assertEquals(0, calls)
        } finally {
            server.close()
        }
    }

    @Test
    fun persistedLiveLeaseMustBeThisServingLease() {
        val runtime = Files.createTempDirectory("control-channel-lease")
        val socket = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        var calls = 0
        val server = ControlServer().serve(socket, token) {
            calls++
            ControlResponse(true, "unexpected")
        }
        try {
            val other = ProcessHandle.current().parent().orElseThrow()
            val otherStart = other.info().startInstant().orElseThrow()
            Files.writeString(
                ControlServer.leasePath(socket),
                "pid=${other.pid()}\nstart=$otherStart\ntoken=$token\n",
            )
            val response = ControlClient().request(
                socket, token, ControlCommand.Reload, Duration.ofSeconds(2),
            )
            assertFalse(response.ok)
            assertEquals("authentication failed", response.message)
            assertEquals(0, calls)
        } finally {
            server.close()
            Files.deleteIfExists(socket)
            Files.deleteIfExists(ControlServer.tokenPath(socket))
            Files.deleteIfExists(ControlServer.leasePath(socket))
        }
    }

    @Test
    fun malformedCommandPreservesErrorResponseFraming() {
        val runtime = Files.createTempDirectory("control-channel-malformed")
        val socket = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        val server = ControlServer().serve(socket, token) { ControlResponse(true, "unexpected") }
        try {
            rawConnect(socket).use { channel ->
                writeAll(channel, "$token\nnot-a-command\n".toByteArray())
                assertEquals("error\nunknown control command\n", readResponse(channel))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun withheldAndOversizedClientFramesCannotStarveLaterRequests() {
        val runtime = Files.createTempDirectory("control-channel-bounds")
        val socket = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        val server = ControlServer().serve(socket, token) { ControlResponse(true, "ok") }
        try {
            val withheld = rawConnect(socket)
            try {
                writeAll(withheld, token.toByteArray())
                assertEquals("ok", ControlClient().request(
                    socket, token, ControlCommand.Reload, Duration.ofSeconds(2),
                ).message)
            } finally {
                withheld.close()
            }

            rawConnect(socket).use { oversized ->
                writeAll(oversized, ByteArray(8 * 1024) { 'x'.code.toByte() })
            }
            assertEquals("ok", ControlClient().request(
                socket, token, ControlCommand.Reload, Duration.ofSeconds(2),
            ).message)
        } finally {
            server.close()
        }
    }

    @Test
    fun embeddedCarriageReturnsAreRejectedWithoutStoppingListener() {
        val runtime = Files.createTempDirectory("control-channel-cr")
        val socket = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        val server = ControlServer().serve(socket, token) { ControlResponse(true, "ok") }
        try {
            rawConnect(socket).use { malformed ->
                writeAll(malformed, "$token\rnot-a-command\n".toByteArray())
            }
            assertEquals("ok", ControlClient().request(
                socket, token, ControlCommand.Reload, Duration.ofSeconds(2),
            ).message)
        } finally {
            server.close()
        }
    }

    @Test
    fun clientWorkersHaveAReservableBoundAndListenerSurvivesSaturation() {
        val runtime = Files.createTempDirectory("control-channel-workers")
        val socket = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        val server = ControlServer().serve(socket, token) { ControlResponse(true, "ok") }
        val held = mutableListOf<SocketChannel>()
        try {
            repeat(64) { held += rawConnect(socket) }
            Thread.sleep(100)
        } finally {
            held.forEach { runCatching { it.close() } }
        }
        try {
            assertEquals("ok", ControlClient().request(
                socket, token, ControlCommand.Reload, Duration.ofSeconds(2),
            ).message)
        } finally {
            server.close()
        }
    }

    @Test
    fun staleCleanupRequiresARealRefusedUnixSocketAndPreservesTamperedPaths() {
        val runtime = Files.createTempDirectory("control-channel-stale")
        val regular = runtime.resolve("regular")
        Files.writeString(regular, "do not delete")
        assertFailsWith<IOException> {
            ControlServer().serve(regular, ControlServer.generateToken()) { ControlResponse(true) }
        }
        assertEquals("do not delete", Files.readString(regular))

        val symlinkTarget = runtime.resolve("target")
        Files.writeString(symlinkTarget, "do not redirect")
        val symlink = runtime.resolve("proxy.control")
        Files.createSymbolicLink(symlink, symlinkTarget)
        assertFailsWith<IOException> {
            ControlServer().serve(symlink, ControlServer.generateToken()) { ControlResponse(true) }
        }
        assertTrue(Files.isSymbolicLink(symlink))
        assertEquals("do not redirect", Files.readString(symlinkTarget))
        Files.delete(symlink)

        val stale = runtime.resolve("stale")
        ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { listener ->
            listener.bind(java.net.UnixDomainSocketAddress.of(stale))
        }
        val server = ControlServer().serve(stale, ControlServer.generateToken()) { ControlResponse(true) }
        server.close()
    }

    @Test
    fun closeNeverDeletesAReplacementSocket() {
        val runtime = Files.createTempDirectory("control-channel-replacement")
        val socket = runtime.resolve("proxy.control")
        val server = ControlServer().serve(socket, ControlServer.generateToken()) { ControlResponse(true) }
        Files.delete(socket)
        val replacement = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        replacement.bind(java.net.UnixDomainSocketAddress.of(socket))
        try {
            server.close()
            assertTrue(Files.exists(socket))
        } finally {
            replacement.close()
            Files.deleteIfExists(socket)
            Files.deleteIfExists(ControlServer.tokenPath(socket))
            Files.deleteIfExists(ControlServer.leasePath(socket))
        }
    }

    @Test
    fun clientRejectsSymlinkedSocketBeforeSendingToken() {
        val runtime = Files.createTempDirectory("control-channel-client-symlink")
        val socket = runtime.resolve("real")
        val link = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        var calls = 0
        val server = ControlServer().serve(socket, token) {
            calls++
            ControlResponse(true, "unexpected")
        }
        Files.createSymbolicLink(link, socket)
        try {
            assertFailsWith<IOException> {
                ControlClient().request(link, token, ControlCommand.Reload, Duration.ofSeconds(1))
            }
            assertEquals(0, calls)
        } finally {
            Files.deleteIfExists(link)
            server.close()
        }
    }

    @Test
    fun generatedTokensAreDistinctAndSufficientlyUnpredictable() {
        val first = ControlServer.generateToken()
        val second = ControlServer.generateToken()
        assertNotEquals(first, second)
        assertTrue(first.length >= 40)
        assertTrue(second.length >= 40)
    }

    private fun assertOwnerOnly(path: Path) {
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return
        val ownerOnly = setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        )
        val directoryOwnerOnly = ownerOnly + java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        assertTrue(permissions == ownerOnly || permissions == directoryOwnerOnly)
    }

    private fun rawConnect(socket: Path): SocketChannel =
        SocketChannel.open(java.net.StandardProtocolFamily.UNIX).apply {
            connect(java.net.UnixDomainSocketAddress.of(socket))
        }

    private fun writeAll(channel: SocketChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun readResponse(channel: SocketChannel): String {
        val output = StringBuilder()
        val one = ByteBuffer.allocate(1)
        var lines = 0
        while (lines < 2) {
            one.clear()
            if (channel.read(one) < 0) break
            val value = one.array()[0].toInt().and(0xff).toChar()
            output.append(value)
            if (value == '\n') lines++
        }
        return output.toString()
    }
}
