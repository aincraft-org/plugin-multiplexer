package io.github.developmentnetwork.runtime.status

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Duration

/** One endpoint's Minecraft status response; reachability is not routing proof. */
data class ServerStatus(
    val reachable: Boolean,
    val version: String? = null,
    val motd: String? = null,
    val playersOnline: Int? = null,
    val playersMax: Int? = null,
    val error: String? = null,
) {
    val online: Boolean get() = reachable
    val success: Boolean get() = reachable
    val maxPlayers: Int? get() = playersMax
    val onlinePlayers: Int? get() = playersOnline
    val onlinePlayerCount: Int? get() = playersOnline
    val maxPlayerCount: Int? get() = playersMax
    val versionName: String? get() = version
    val motdText: String? get() = motd
}

/** Direct Minecraft handshake/status client with bounded connect and read operations. */
class MinecraftStatusProbe {
    fun probe(host: String, port: Int, timeout: Duration): ServerStatus {
        if (host.isBlank()) return failure("host is blank")
        if (port !in 1..65535) return failure("invalid port $port")
        val timeoutMillis = timeout.toMillis()
        if (timeoutMillis <= 0) return failure("timeout must be positive")
        val boundedTimeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), boundedTimeout)
                socket.soTimeout = boundedTimeout
                val output = socket.getOutputStream()
                writeFrame(output, handshake(port, host))
                writeFrame(output, byteArrayOf(0))
                output.flush()
                parseResponse(socket.getInputStream())
            }
        } catch (error: Exception) {
            failure(error.message ?: error::class.simpleName ?: "status probe failed")
        }
    }

    private fun handshake(port: Int, host: String): ByteArray {
        val payload = ByteArrayOutputStream()
        writeVarInt(payload, 0)
        // -1 is accepted by current servers as an intentionally unspecified protocol.
        writeVarInt(payload, -1)
        writeString(payload, host)
        payload.write((port ushr 8) and 0xff)
        payload.write(port and 0xff)
        writeVarInt(payload, 1)
        return payload.toByteArray()
    }

    private fun parseResponse(input: java.io.InputStream): ServerStatus {
        val packetLength = readVarInt(input)
        require(packetLength in 1..MAX_PACKET_SIZE) { "invalid status packet length $packetLength" }
        val packet = readExact(input, packetLength)
        val packetInput = ByteArrayInputStream(packet)
        require(readVarInt(packetInput) == 0) { "unexpected status packet id" }
        val jsonLength = readVarInt(packetInput)
        require(jsonLength in 2..MAX_JSON_SIZE && jsonLength <= packetInput.available()) {
            "invalid status JSON length $jsonLength"
        }
        val json = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(readExact(packetInput, jsonLength))).toString()
        val value = JsonParser(json).parse()
        require(value is Map<*, *>) { "status response is not a JSON object" }
        val version = (value["version"] as? Map<*, *>)?.get("name")?.toString()
        val players = value["players"] as? Map<*, *>
        val online = (players?.get("online") as? Number)?.toInt()
        val max = (players?.get("max") as? Number)?.toInt()
        val description = description(value["description"])
        return ServerStatus(true, version, description, online, max)
    }

    private fun description(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Map<*, *> -> value["text"]?.toString() ?: value.values.joinToString("") { description(it).orEmpty() }
        is List<*> -> value.joinToString("") { description(it).orEmpty() }
        else -> value.toString()
    }

    private fun writeFrame(output: java.io.OutputStream, payload: ByteArray) {
        writeVarInt(output, payload.size)
        output.write(payload)
    }

    private fun writeString(output: java.io.OutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_JSON_SIZE) { "host is too long" }
        writeVarInt(output, bytes.size)
        output.write(bytes)
    }

    private fun readVarInt(input: java.io.InputStream): Int {
        var result = 0
        var shift = 0
        repeat(5) {
            val next = input.read()
            if (next < 0) throw EOFException("connection closed while reading VarInt")
            result = result or ((next and 0x7f) shl shift)
            if (next and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("VarInt is too long")
    }

    private fun readExact(input: java.io.InputStream, length: Int): ByteArray {
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(output, offset, length - offset)
            if (count < 0) throw EOFException("connection closed after $offset/$length bytes")
            if (count > 0) offset += count
        }
        return output
    }

    private fun writeVarInt(output: java.io.OutputStream, value: Int) {
        var remaining = value
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            output.write(next)
        } while (remaining != 0)
    }

    private fun failure(message: String): ServerStatus = ServerStatus(reachable = false, error = message)

    private companion object {
        const val MAX_PACKET_SIZE = 2 * 1024 * 1024
        const val MAX_JSON_SIZE = 2 * 1024 * 1024
    }
}

/** Small bounded JSON parser for the status response; avoids adding a runtime dependency. */
private class JsonParser(private val source: String) {
    private var index = 0

    fun parse(): Any? {
        val value = value()
        whitespace()
        require(index == source.length) { "trailing JSON data" }
        return value
    }

    private fun value(): Any? {
        whitespace()
        require(index < source.length) { "unexpected end of JSON" }
        return when (source[index]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> numberValue()
        }
    }

    private fun objectValue(): Map<String, Any?> {
        index++
        val result = LinkedHashMap<String, Any?>()
        whitespace()
        if (consume('}')) return result
        while (true) {
            whitespace()
            require(index < source.length && source[index] == '"') { "object key must be a string" }
            val key = stringValue()
            whitespace()
            require(consume(':')) { "missing object colon" }
            result[key] = value()
            whitespace()
            if (consume('}')) return result
            require(consume(',')) { "missing object comma" }
        }
    }

    private fun arrayValue(): List<Any?> {
        index++
        val result = ArrayList<Any?>()
        whitespace()
        if (consume(']')) return result
        while (true) {
            result += value()
            whitespace()
            if (consume(']')) return result
            require(consume(',')) { "missing array comma" }
        }
    }

    private fun stringValue(): String {
        require(consume('"')) { "string must start with quote" }
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when (character) {
                '"' -> return result.toString()
                '\\' -> {
                    require(index < source.length) { "incomplete JSON escape" }
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= source.length) { "incomplete JSON unicode escape" }
                            val code = source.substring(index, index + 4).toIntOrNull(16)
                            require(code != null) { "invalid JSON unicode escape" }
                            result.append(code.toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("invalid JSON escape")
                    }
                }
                else -> {
                    require(character.code >= 0x20) { "control character in JSON string" }
                    result.append(character)
                }
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    private fun numberValue(): Number {
        val start = index
        if (index < source.length && source[index] == '-') index++
        while (index < source.length && source[index].isDigit()) index++
        if (index < source.length && source[index] == '.') {
            index++
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index++
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            while (index < source.length && source[index].isDigit()) index++
        }
        val raw = source.substring(start, index)
        require(raw.isNotEmpty() && raw != "-") { "invalid JSON value" }
        return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw IllegalArgumentException("invalid JSON number")
    }

    private fun <T> literal(expected: String, value: T): T {
        require(source.regionMatches(index, expected, 0, expected.length)) { "invalid JSON literal" }
        index += expected.length
        return value
    }

    private fun whitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun consume(character: Char): Boolean = if (index < source.length && source[index] == character) {
        index++
        true
    } else false
}
