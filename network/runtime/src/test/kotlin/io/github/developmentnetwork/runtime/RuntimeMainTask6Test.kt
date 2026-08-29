package io.github.developmentnetwork.runtime

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RuntimeMainTask6Test {
    @Test
    fun `managed backend command may omit port and carries custom infrastructure reservations`() {
        val command = parseRuntimeCommand(
            listOf(
                "runBackend",
                "--base=/tmp/network",
                "--name=backend",
                "--backend-dir=/tmp/network/runtime/auto/backend",
                "--owner=gradle-owner",
                "--proxy-owner=proxy-owner",
                "--proxy-port=25400",
                "--lobby-port=30100",
                "--dev-users=alice,bob",
            ),
        )
        assertTrue(command is RuntimeCommand.ServeBackend)
        assertNull((command as RuntimeCommand.ServeBackend).request.port)
        assertEquals("proxy-owner", command.request.proxyOwner)
        assertEquals(25400, command.request.proxyPort)
        assertEquals(30100, command.request.lobbyPort)
        assertEquals(listOf("alice", "bob"), command.request.devUsers)
    }
    @Test
    fun `proxy command accepts controller timeout with complete infrastructure settings`() {
        val command = parseRuntimeCommand(
            listOf(
                "runProxy",
                "--base=/tmp/network",
                "--proxy-port=25400",
                "--lobby-port=30100",
                "--owner=gradle-owner",
                "--timeout=240",
                "--shutdown-timeout=30",
                "--control-timeout=5",
                "--online-mode=false",
                "--dev-users=alice,bob",
            ),
        )

        assertTrue(command is RuntimeCommand.ServeProxy)
        val request = (command as RuntimeCommand.ServeProxy).request
        assertEquals(25400, request.proxyPort)
        assertEquals(30100, request.lobbyPort)
        assertEquals("gradle-owner", request.owner)
        assertEquals(240L, request.readinessTimeout.seconds)
        assertEquals(30L, request.shutdownTimeout.seconds)
        assertEquals(false, request.onlineMode)
        assertEquals(listOf("alice", "bob"), request.devUsers)
    }
}
