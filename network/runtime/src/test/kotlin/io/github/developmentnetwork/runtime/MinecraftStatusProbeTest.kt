package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.status.MinecraftStatusProbe
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftStatusProbeTest {
    @Test
    fun parsesStatusResponseWithStructuredDescriptionAndPlayers() {
        val server = ServerSocket(0)
        val worker = thread(start = true) {
            server.use { listener ->
                listener.accept().use { socket ->
                    consumeRequest(socket)
                    val json = "{\"version\":{\"name\":\"Paper 26.2\"},\"description\":{\"text\":\"Lobby\"},\"players\":{\"online\":2,\"max\":20}}"
                    sendStatus(socket, json)
                }
            }
        }
        val status = MinecraftStatusProbe().probe("127.0.0.1", server.localPort, Duration.ofSeconds(2))
        worker.join()
        assertTrue(status.reachable, status.error.orEmpty())
        assertEquals("Paper 26.2", status.version)
        assertEquals("Lobby", status.motd)
        assertEquals(2, status.playersOnline)
        assertEquals(20, status.playersMax)
    }

    @Test
    fun malformedResponseAndUnavailableEndpointAreStructuredFailures() {
        val malformed = ServerSocket(0)
        val worker = thread(start = true) {
            malformed.use { listener ->
                listener.accept().use { socket ->
                    consumeRequest(socket)
                    sendStatus(socket, "not-json")
                }
            }
        }
        val malformedResult = MinecraftStatusProbe().probe("127.0.0.1", malformed.localPort, Duration.ofSeconds(2))
        worker.join()
        assertFalse(malformedResult.reachable)
        assertTrue(!malformedResult.error.isNullOrBlank())

        val unavailable = MinecraftStatusProbe().probe("127.0.0.1", 1, Duration.ofMillis(100))
        assertFalse(unavailable.reachable)
        assertTrue(!unavailable.error.isNullOrBlank())
    }

    private fun consumeRequest(socket: Socket) {
        socket.soTimeout = 2_000
        val handshakeLength = readVarInt(socket)
        socket.inputStream.readNBytes(handshakeLength)
        val requestLength = readVarInt(socket)
        socket.inputStream.readNBytes(requestLength)
    }

    private fun sendStatus(socket: Socket, json: String) {
        val packet = ByteArrayOutputStreamBuilder().apply {
            writeVarInt(0)
            writeString(json)
        }.bytes()
        val output = DataOutputStream(socket.getOutputStream())
        writeVarInt(output, packet.size)
        output.write(packet)
        output.flush()
    }

    private fun readVarInt(socket: Socket): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = socket.inputStream.read()
            if (byte < 0) return result
            result = result or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    private class ByteArrayOutputStreamBuilder {
        private val output = java.io.ByteArrayOutputStream()
        fun writeVarInt(value: Int) {
            var remaining = value
            do {
                var next = remaining and 0x7f
                remaining = remaining ushr 7
                if (remaining != 0) next = next or 0x80
                output.write(next)
            } while (remaining != 0)
        }
        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeVarInt(bytes.size)
            output.write(bytes)
        }
        fun write(bytes: ByteArray) = output.write(bytes)
        fun bytes(): ByteArray = output.toByteArray()
    }

    private fun writeVarInt(output: DataOutputStream, value: Int) {
        var remaining = value
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            output.writeByte(next)
        } while (remaining != 0)
    }

    private fun writeVarInt(output: java.io.ByteArrayOutputStream, value: Int) {
        var remaining = value
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            output.write(next)
        } while (remaining != 0)
    }
}
