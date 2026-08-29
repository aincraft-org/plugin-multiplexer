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
    fun `external registration accepts distinct proxy target and backend host`() {
        val command = parseRuntimeCommand(
            listOf(
                "registerBackend",
                "--base=/tmp/network",
                "--name=backend",
                "--port=25566",
                "--registration-owner=gradle-owner",
                "--server-dir=/tmp/network/backend",
                "--target-server=proxy.example",
                "--host=backend.example",
                "--timeout=240",
                "--lobby-port=30066",
                "--control-timeout=5",
            ),
        )
        assertTrue(command is RuntimeCommand.RegisterExternal)
        val request = (command as RuntimeCommand.RegisterExternal).request
        assertEquals("proxy.example", request.targetServer)
        assertEquals("backend.example", request.host)
    }
}
