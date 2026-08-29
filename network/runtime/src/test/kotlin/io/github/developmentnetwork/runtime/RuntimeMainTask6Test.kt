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
}
