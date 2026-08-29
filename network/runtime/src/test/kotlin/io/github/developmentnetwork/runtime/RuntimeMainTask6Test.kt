package io.github.developmentnetwork.runtime

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RuntimeMainTask6Test {
    @Test
    fun `managed backend command may omit port for runtime allocation`() {
        val command = parseRuntimeCommand(
            listOf(
                "runBackend",
                "--base=/tmp/network",
                "--name=backend",
                "--backend-dir=/tmp/network/runtime/auto/backend",
                "--owner=gradle-owner",
                "--dev-users=alice,bob",
            ),
        )
        assertTrue(command is RuntimeCommand.ServeBackend)
        assertNull((command as RuntimeCommand.ServeBackend).request.port)
        assertEquals(listOf("alice", "bob"), (command as RuntimeCommand.ServeBackend).request.devUsers)
    }
}
