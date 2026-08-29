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
import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeoutException

/** Bounded client for a controller's authenticated Unix-domain control socket. */
class ControlClient {
    fun request(socket: Path, token: String, command: ControlCommand, timeout: Duration): ControlResponse {
        require(!timeout.isNegative) { "Control request timeout must not be negative" }
        val address = socket.toAbsolutePath().normalize()
        if (!Files.exists(address, NOFOLLOW_LINKS) || Files.isRegularFile(address, NOFOLLOW_LINKS)) {
            throw IOException("Control socket is unavailable or stale: $socket")
        }

        val persistedToken = runCatching {
            Files.readString(ControlServer.tokenPath(address), StandardCharsets.UTF_8)
                .trimEnd('\r', '\n')
        }.getOrElse { throw SecurityException("Control token state is unavailable", it) }
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
            val connected = channel.connect(java.net.UnixDomainSocketAddress.of(address))
            Selector.open().use { selector ->
                if (!connected) {
                    channel.register(selector, SelectionKey.OP_CONNECT)
                    awaitReady(selector, channel, SelectionKey.OP_CONNECT, deadline)
                    if (!channel.isConnected) channel.finishConnect()
                }
                writeFully(channel, selector, ByteBuffer.wrap(request), deadline)
                val bytes = readResponse(channel, selector, deadline)
                return decode(bytes)
            }
        }
    }

    private fun writeFully(channel: SocketChannel, selector: Selector, data: ByteBuffer, deadline: Long) {
        while (data.hasRemaining()) {
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
            val millis = (remaining / 1_000_000L).coerceAtLeast(1L)
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

    private fun readLease(path: Path): Lease? {
        val lines = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val values = lines.mapNotNull { line ->
            line.indexOf('=').takeIf { it > 0 }?.let { index -> line.substring(0, index) to line.substring(index + 1) }
        }.toMap()
        val pid = values["pid"]?.toLongOrNull() ?: return null
        val start = runCatching { Instant.parse(values["start"] ?: return null) }.getOrNull() ?: return null
        return Lease(pid, start, values["token"] ?: return null)
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        java.security.MessageDigest.isEqual(
            first.toByteArray(StandardCharsets.UTF_8),
            second.toByteArray(StandardCharsets.UTF_8),
        )

    private data class Lease(val pid: Long, val start: Instant, val token: String) {
        fun isLive(): Boolean {
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            return handle.isAlive && handle.info().startInstant().orElse(null) == start
        }
    }

    private fun deadlineNanos(timeout: Duration): Long {
        val nanos = try {
            timeout.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val now = System.nanoTime()
        return if (nanos >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + nanos
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024
    }
}
