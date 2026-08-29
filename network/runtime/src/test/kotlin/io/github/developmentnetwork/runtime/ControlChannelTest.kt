package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlResponse
import io.github.developmentnetwork.runtime.controller.ControlServer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
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
    fun missingOrStaleLeaseFailsClosedAndStaleSocketIsNotReused() {
        val runtime = Files.createTempDirectory("control-channel-stale")
        val socket = runtime.resolve("proxy.control")
        val token = ControlServer.generateToken()
        val server = ControlServer().serve(socket, token) { ControlResponse(true, "ok") }
        Files.delete(ControlServer.leasePath(socket))
        try {
            assertFailsWith<SecurityException> {
                ControlClient().request(socket, token, ControlCommand.Reload, Duration.ofSeconds(1))
            }
        } finally {
            server.close()
        }
        Files.deleteIfExists(socket)
        Files.writeString(socket, "stale socket")

        assertFailsWith<IOException> {
            ControlClient().request(socket, token, ControlCommand.Reload, Duration.ofMillis(100))
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
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        assertEquals(ownerOnly, permissions)
    }
}
