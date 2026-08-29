package io.github.developmentnetwork.runtime.controller

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

/** Bounded client for a controller's authenticated Unix-domain control socket. */
class ControlClient {
    fun request(socket: Path, token: String, command: ControlCommand, timeout: Duration): ControlResponse {
        require(!timeout.isNegative) { "Control request timeout must not be negative" }
        require(token.isNotBlank() && '\n' !in token && '\r' !in token) {
            "Control token must be a non-blank single line"
        }
        require(token.toByteArray(StandardCharsets.UTF_8).size <= MAX_REQUEST_LINE_BYTES) {
            "Control token is too large"
        }
        val address = socket.toAbsolutePath().normalize()
        ControlSocketSecurity.rejectSymlinkComponents(address)
        val node = ControlSocketSecurity.requireSocketNode(address)
        val persistedToken = readToken(ControlServer.tokenPath(address))
            ?: throw SecurityException("Control token state is unavailable")
        val lease = readLease(ControlServer.leasePath(address))
            ?: throw SecurityException("Controller lease is unavailable or malformed")
        if (lease.token != persistedToken || !lease.isLive()) {
            throw SecurityException("Controller lease is stale")
        }
        if (!constantTimeEquals(token, persistedToken)) {
            return ControlResponse.failure(ControlWire.AUTHENTICATION_FAILED)
        }

        val deadline = deadlineNanos(timeout)
        val request = (token + "\n" + ControlWire.encodeCommand(command) + "\n")
            .toByteArray(StandardCharsets.UTF_8)
        SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            // Connecting does not send credentials. Verify that the path still names
            // the checked socket before any token bytes are written.
            val connected = channel.connect(java.net.UnixDomainSocketAddress.of(address))
            Selector.open().use { selector ->
                if (!connected) {
                    channel.register(selector, SelectionKey.OP_CONNECT)
                    awaitReady(selector, channel, SelectionKey.OP_CONNECT, deadline)
                    if (!channel.isConnected) channel.finishConnect()
                }
                if (!ControlSocketSecurity.sameNode(address, node)) {
                    throw IOException("Control socket changed while connecting: $socket")
                }
                writeFully(channel, selector, ByteBuffer.wrap(request), deadline, address, node)
                val bytes = readResponse(channel, selector, deadline)
                return decode(bytes)
            }
        }
    }

    private fun writeFully(
        channel: SocketChannel,
        selector: Selector,
        data: ByteBuffer,
        deadline: Long,
        address: Path,
        node: ControlSocketSecurity.SocketNode,
    ) {
        while (data.hasRemaining()) {
            // Recheck before every write to narrow the path replacement window as far
            // as standard Java NIO permits; no token is emitted for a failed check.
            if (!ControlSocketSecurity.sameNode(address, node)) {
                throw IOException("Control socket changed while writing: $address")
            }
            val count = channel.write(data)
            if (count > 0) continue
            awaitReady(selector, channel, SelectionKey.OP_WRITE, deadline)
        }
    }

    private fun readResponse(channel: SocketChannel, selector: Selector, deadline: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(512)
        var lines = 0
        while (lines < 2) {
            buffer.clear()
            val count = channel.read(buffer)
            if (count == -1) break
            if (count == 0) {
                awaitReady(selector, channel, SelectionKey.OP_READ, deadline)
                continue
            }
            buffer.flip()
            while (buffer.hasRemaining()) {
                val value = buffer.get().toInt().and(0xff)
                output.write(value)
                if (value == '\n'.code) lines++
                if (output.size() > MAX_RESPONSE_BYTES) throw IOException("Control response is too large")
                if (lines >= 2) break
            }
        }
        if (lines < 2) throw IOException("Incomplete control response")
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): ControlResponse {
        val text = bytes.toString(StandardCharsets.UTF_8)
        val lines = text.split('\n')
        if (lines.size < 2) throw IOException("Malformed control response")
        return ControlWire.decodeResponse(lines[0], lines[1])
    }

    private fun awaitReady(selector: Selector, channel: SocketChannel, operation: Int, deadline: Long) {
        channel.keyFor(selector)?.interestOps(operation) ?: channel.register(selector, operation)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for controller control socket")
            val millis = ((remaining + 999_999L) / 1_000_000L).coerceAtLeast(1L)
            if (selector.select(millis) == 0) continue
            val keys = selector.selectedKeys()
            val ready = keys.any { key ->
                val usable = key.isValid && (key.readyOps() and operation) != 0
                if (usable) key.interestOps(0)
                usable
            }
            keys.clear()
            if (ready) return
        }
    }

    private fun readToken(path: Path): String? = readState(path)?.trimEnd('\r', '\n')

    private fun readLease(path: Path): Lease? {
        val text = readState(path) ?: return null
        val lines = text.split('\n').let { values -> if (values.lastOrNull() == "") values.dropLast(1) else values }
        if (lines.size != 3) return null
        val values = mutableMapOf<String, String>()
        lines.forEach { line ->
            val index = line.indexOf('=')
            if (index <= 0 || values.put(line.substring(0, index), line.substring(index + 1)) != null) return null
        }
        val pid = values["pid"]?.toLongOrNull() ?: return null
        val start = runCatching { Instant.parse(values["start"] ?: return null) }.getOrNull() ?: return null
        return Lease(pid, start, values["token"] ?: return null)
    }

    private fun readState(path: Path): String? {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size > MAX_STATE_BYTES) return null
        return runCatching { Files.readAllBytes(path).toString(StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        java.security.MessageDigest.isEqual(first.toByteArray(StandardCharsets.UTF_8), second.toByteArray(StandardCharsets.UTF_8))

    private data class Lease(val pid: Long, val start: Instant, val token: String) {
        fun isLive(): Boolean {
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            return handle.isAlive && handle.info().startInstant().orElse(null) == start
        }
    }

    private fun deadlineNanos(timeout: Duration): Long {
        val nanos = try { timeout.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        val now = System.nanoTime()
        return if (nanos >= 0L && now > Long.MAX_VALUE - nanos) Long.MAX_VALUE else now + nanos
    }

    private companion object {
        const val MAX_REQUEST_LINE_BYTES = 4 * 1024
        const val MAX_RESPONSE_BYTES = 16 * 1024
        const val MAX_STATE_BYTES = 4 * 1024
    }
}
