package io.github.developmentnetwork.runtime.controller

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.FileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Unix-domain control listener for one live runtime controller. */
class ControlServer {
    fun serve(
        socket: Path,
        token: String = generateToken(),
        handler: (ControlCommand) -> ControlResponse,
    ): Closeable {
        require(token.isNotBlank() && '\n' !in token && '\r' !in token) {
            "Control token must be a non-blank single line"
        }
        require(token.toByteArray(StandardCharsets.UTF_8).size <= MAX_REQUEST_LINE_BYTES) {
            "Control token is too large"
        }
        val address = socket.toAbsolutePath().normalize()
        ControlSocketSecurity.rejectSymlinkComponents(address)
        val parent = address.parent ?: error("Control socket must have a parent directory: $socket")
        Files.createDirectories(parent)
        ControlSocketSecurity.rejectSymlinkComponents(address)
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) {
            throw IOException("Control socket parent is not a directory: $parent")
        }
        // The directory is private before bind makes the socket reachable. This is
        // the access-control boundary; chmod-after-bind alone would expose a window.
        setOwnerOnlyDirectory(parent)
        prepareSocket(address)

        val current = currentLease(token)
        val channel = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        var bound = false
        var boundNode: ControlSocketSecurity.SocketNode? = null
        try {
            channel.bind(java.net.UnixDomainSocketAddress.of(address))
            bound = true
            boundNode = ControlSocketSecurity.requireSocketNode(address)
            setOwnerOnly(address)
            writeSecure(tokenPath(address), "$token\n")
            writeSecure(leasePath(address), current.encode())
        } catch (error: Throwable) {
            runCatching { channel.close() }
            if (bound && boundNode != null) {
                runCatching { ControlSocketSecurity.deleteIfSame(address, boundNode) }
            }
            throw error
        }

        val running = AtomicBoolean(true)
        val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<SocketChannel>()
        val workers = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
        val workerSlots = java.util.concurrent.Semaphore(MAX_CLIENT_WORKERS)
        val listener = Thread({
            while (running.get()) {
                try {
                    val client = channel.accept()
                    if (!workerSlots.tryAcquire()) {
                        runCatching { client.close() }
                        continue
                    }
                    clients.add(client)
                    val worker = Thread({
                        try {
                            serveRequest(client, address, token, current, handler)
                        } catch (_: Exception) {
                            // A malformed, timed-out, or disconnected client cannot
                            // terminate the listener or affect other clients.
                        } finally {
                            clients.remove(client)
                            workers.remove(Thread.currentThread())
                            workerSlots.release()
                            runCatching { client.close() }
                        }
                    }, "runtime-control-client-${address.fileName}").apply { isDaemon = true }
                    workers.add(worker)
                    try {
                        worker.start()
                    } catch (error: Throwable) {
                        workers.remove(worker)
                        clients.remove(client)
                        workerSlots.release()
                        runCatching { client.close() }
                        throw error
                    }
                } catch (_: AsynchronousCloseException) {
                    break
                } catch (_: IOException) {
                    if (!running.get()) break
                }
            }
        }, "runtime-control-${address.fileName}").apply { isDaemon = true }
        listener.start()

        return Closeable {
            if (!running.compareAndSet(true, false)) return@Closeable
            runCatching { channel.close() }
            clients.forEach { runCatching { it.close() } }
            listener.interrupt()
            runCatching { listener.join(CLOSE_JOIN_MILLIS) }
            workers.forEach { it.interrupt() }
            workers.forEach { runCatching { it.join(CLOSE_JOIN_MILLIS) } }
            removeOwnedState(address, current, boundNode)
        }
    }

    private fun serveRequest(
        client: SocketChannel,
        socket: Path,
        expectedToken: String,
        servingLease: Lease,
        handler: (ControlCommand) -> ControlResponse,
    ) {
        client.configureBlocking(false)
        Selector.open().use { selector ->
            val deadline = deadlineNanos(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            val suppliedToken = readLine(client, selector, deadline) ?: return
            val commandText = readLine(client, selector, deadline) ?: return
            if (!isAuthenticated(socket, expectedToken, suppliedToken, servingLease)) {
                writeResponse(client, selector, ControlResponse.failure(ControlWire.AUTHENTICATION_FAILED), deadline)
                return
            }
            val command = ControlWire.decodeCommand(commandText)
            if (command == null) {
                writeResponse(client, selector, ControlResponse.failure("unknown control command"), deadline)
                return
            }
            val response = runCatching { handler(command) }
                .getOrElse { error -> ControlResponse.failure(error.message ?: "control handler failed") }
            writeResponse(client, selector, response, deadline)
        }
    }

    private fun readLine(client: SocketChannel, selector: Selector, deadline: Long): String? {
        val output = java.io.ByteArrayOutputStream()
        val one = ByteBuffer.allocate(1)
        var frameBytes = 0
        var carriageReturn = false
        while (true) {
            one.clear()
            val count = client.read(one)
            if (count < 0) return null
            if (count == 0) {
                awaitReady(selector, client, SelectionKey.OP_READ, deadline)
                continue
            }
            frameBytes += count
            if (frameBytes > MAX_REQUEST_LINE_BYTES) {
                throw IOException("Control request frame is too large")
            }
            val value = one.array()[0].toInt() and 0xff
            when {
                value == '\r'.code -> {
                    if (carriageReturn) throw IOException("Embedded carriage return in control frame")
                    carriageReturn = true
                }
                value == '\n'.code -> {
                    return output.toString(StandardCharsets.UTF_8)
                }
                carriageReturn -> throw IOException("Embedded carriage return in control frame")
                else -> output.write(value)
            }
        }
    }

    private fun writeResponse(
        client: SocketChannel,
        selector: Selector,
        response: ControlResponse,
        deadline: Long,
    ) {
        val encoded = ControlWire.encodeResponse(response).toByteArray(StandardCharsets.UTF_8)
        val data = if (encoded.size <= MAX_RESPONSE_BYTES) {
            encoded
        } else {
            ControlWire.encodeResponse(ControlResponse.failure("control response is too large"))
                .toByteArray(StandardCharsets.UTF_8)
        }
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) {
            if (client.write(buffer) == 0) awaitReady(selector, client, SelectionKey.OP_WRITE, deadline)
        }
    }

    private fun isAuthenticated(
        socket: Path,
        expectedToken: String,
        suppliedToken: String,
        servingLease: Lease,
    ): Boolean {
        if (!constantTimeEquals(expectedToken, suppliedToken)) return false
        val persisted = readToken(tokenPath(socket)) ?: return false
        if (!constantTimeEquals(expectedToken, persisted)) return false
        val lease = readLease(leasePath(socket)) ?: return false
        // Every request must still belong to this exact serving lease, not merely to
        // any live process that happens to hold the persisted token.
        if (lease.pid != servingLease.pid || lease.start != servingLease.start) return false
        if (!constantTimeEquals(lease.token, servingLease.token)) return false
        if (!lease.isLive()) return false
        return true
    }

    private fun prepareSocket(socket: Path) {
        ControlSocketSecurity.rejectSymlinkComponents(socket)
        if (!Files.exists(socket, NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(socket)) {
            throw IOException("Control socket path is a symlink: $socket")
        }
        val oldLease = readLease(leasePath(socket))
        if (oldLease?.isLive() == true) {
            throw IOException("Control socket is owned by a live controller: $socket")
        }
        val node = ControlSocketSecurity.requireSocketNode(socket)
        when (probeSocket(socket)) {
            ProbeOutcome.LIVE -> throw IOException("Control socket has a live listener: $socket")
            ProbeOutcome.INDETERMINATE -> throw IOException("Control socket state cannot be proven stale: $socket")
            ProbeOutcome.STALE -> removeStaleState(socket, node, oldLease)
        }
    }

    private fun probeSocket(socket: Path): ProbeOutcome {
        try {
            SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                channel.configureBlocking(false)
                if (channel.connect(java.net.UnixDomainSocketAddress.of(socket))) return ProbeOutcome.LIVE
                Selector.open().use { selector ->
                    channel.register(selector, SelectionKey.OP_CONNECT)
                    val deadline = deadlineNanos(Duration.ofMillis(PROBE_TIMEOUT_MILLIS))
                    while (true) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0L) return ProbeOutcome.INDETERMINATE
                        val millis = ((remaining + 999_999L) / 1_000_000L).coerceAtLeast(1L)
                        if (selector.select(millis) == 0) continue
                        val ready = selector.selectedKeys().any { key ->
                            key.isValid && (key.readyOps() and SelectionKey.OP_CONNECT) != 0
                        }
                        selector.selectedKeys().clear()
                        if (!ready) continue
                        return try {
                            if (channel.finishConnect()) {
                                ProbeOutcome.LIVE
                            } else {
                                ProbeOutcome.INDETERMINATE
                            }
                        } catch (error: java.net.ConnectException) {
                            explicitRefusal(error)
                        }
                    }
                }
            }
        } catch (error: java.net.ConnectException) {
            return explicitRefusal(error)
        } catch (_: IOException) {
            // Permission, resource, interruption, missing path, and all other errors
            // are indeterminate and therefore refuse cleanup.
            return ProbeOutcome.INDETERMINATE
        } catch (_: SecurityException) {
            return ProbeOutcome.INDETERMINATE
        }
    }

    /**
     * A ConnectException is not by itself portable evidence of ECONNREFUSED. Only
     * the platform's explicit refusal text authorizes unlinking a stale node; all
     * other forms fail closed.
     */
    private fun explicitRefusal(error: java.net.ConnectException): ProbeOutcome {
        val message = error.message?.trim()?.lowercase() ?: return ProbeOutcome.INDETERMINATE
        return if (
            message == "connection refused" ||
            message.endsWith(": connection refused") ||
            message.contains("econnrefused")
        ) {
            ProbeOutcome.STALE
        } else {
            ProbeOutcome.INDETERMINATE
        }
    }

    private fun removeStaleState(
        socket: Path,
        node: ControlSocketSecurity.SocketNode,
        oldLease: Lease?,
    ) {
        ControlSocketSecurity.deleteIfSame(socket, node)
        if (oldLease != null) {
            if (readLease(leasePath(socket)) == oldLease &&
                readToken(tokenPath(socket))?.let { constantTimeEquals(it, oldLease.token) } == true
            ) {
                Files.deleteIfExists(tokenPath(socket))
                Files.deleteIfExists(leasePath(socket))
            }
        }
    }

    private fun removeOwnedState(
        socket: Path,
        lease: Lease,
        boundNode: ControlSocketSecurity.SocketNode?,
    ) {
        val currentLease = readLease(leasePath(socket)) ?: return
        if (currentLease.pid != lease.pid || currentLease.start != lease.start) return
        if (!constantTimeEquals(currentLease.token, lease.token)) return
        if (readToken(tokenPath(socket))?.let { constantTimeEquals(it, lease.token) } != true) return
        if (boundNode != null && Files.exists(socket, NOFOLLOW_LINKS) &&
            !ControlSocketSecurity.sameNode(socket, boundNode)
        ) return
        if (boundNode != null) ControlSocketSecurity.deleteIfSame(socket, boundNode)
        Files.deleteIfExists(tokenPath(socket))
        Files.deleteIfExists(leasePath(socket))
    }

    private fun currentLease(token: String): Lease {
        val process = ProcessHandle.current()
        val start = process.info().startInstant().orElse(null)
            ?: throw IllegalStateException("Current controller has no process start identity")
        return Lease(process.pid(), start, token)
    }

    private fun readToken(path: Path): String? =
        readState(path)?.trimEnd('\r', '\n')

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
        val token = values["token"] ?: return null
        return Lease(pid, start, token)
    }

    private fun readState(path: Path): String? {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size > MAX_STATE_BYTES) return null
        return runCatching { Files.readAllBytes(path).toString(StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun writeSecure(path: Path, content: String) {
        ControlSocketSecurity.rejectSymlinkComponents(path)
        if (Files.isSymbolicLink(path)) throw IOException("Control state path is a symlink: $path")
        val parent = path.parent ?: error("State file must have a parent: $path")
        val attributes = PosixFilePermissions.asFileAttribute(OWNER_ONLY)
        val temporary = Files.createTempFile(parent, ".${path.fileName}-", ".tmp", attributes)
        try {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(temporary, WRITE).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                }
                channel.force(true)
            }
            setOwnerOnly(temporary)
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            setOwnerOnly(path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun setOwnerOnlyDirectory(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, DIRECTORY_OWNER_ONLY) }
            .onFailure { error -> if (error !is UnsupportedOperationException) throw error }
    }

    private fun setOwnerOnly(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }
            .onFailure { error -> if (error !is UnsupportedOperationException) throw error }
    }

    private fun awaitReady(selector: Selector, channel: SocketChannel, operation: Int, deadline: Long) {
        channel.keyFor(selector)?.interestOps(operation) ?: channel.register(selector, operation)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for control client frame")
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

    private fun deadlineNanos(timeout: Duration): Long {
        val nanos = try { timeout.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        val now = System.nanoTime()
        return if (nanos >= 0L && now > Long.MAX_VALUE - nanos) Long.MAX_VALUE else now + nanos
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(first.toByteArray(StandardCharsets.UTF_8), second.toByteArray(StandardCharsets.UTF_8))

    private data class Lease(val pid: Long, val start: Instant, val token: String) {
        fun encode(): String = "pid=$pid\nstart=$start\ntoken=$token\n"
        fun isLive(): Boolean {
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            return handle.isAlive && handle.info().startInstant().orElse(null) == start
        }
    }

    private enum class ProbeOutcome { LIVE, STALE, INDETERMINATE }

    companion object {
        private val RANDOM = SecureRandom()
        private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val DIRECTORY_OWNER_ONLY = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val REQUEST_TIMEOUT_SECONDS = 1L
        private const val PROBE_TIMEOUT_MILLIS = 250L
        private const val MAX_CLIENT_WORKERS = 32
        private const val MAX_REQUEST_LINE_BYTES = 4 * 1024
        private const val MAX_RESPONSE_BYTES = 16 * 1024
        private const val MAX_STATE_BYTES = 4 * 1024

        /** Generate a 256-bit URL-safe owner token. */
        @JvmStatic
        fun generateToken(): String {
            val bytes = ByteArray(32)
            RANDOM.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        @JvmStatic
        fun tokenPath(socket: Path): Path {
            val path = socket.toAbsolutePath().normalize()
            return path.resolveSibling("${path.fileName}.token")
        }

        @JvmStatic
        fun leasePath(socket: Path): Path {
            val path = socket.toAbsolutePath().normalize()
            return path.resolveSibling("${path.fileName}.lease")
        }
    }
}

/** Best-effort Java NIO identity checks used to fail closed on socket path races. */
internal object ControlSocketSecurity {
    data class SocketNode(val fileKey: Any)

    fun rejectSymlinkComponents(path: Path) {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw IOException("Control path contains a symlink: $path")
            current = current.parent
        }
    }

    fun requireSocketNode(path: Path): SocketNode {
        rejectSymlinkComponents(path)
        if (!Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw IOException("Control socket is unavailable or is a symlink: $path")
        }
        val attrs = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        }.getOrElse { throw IOException("Control socket attributes are unavailable: $path", it) }
        val mode = runCatching { Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as? Int }
            .getOrNull() ?: throw IOException("Cannot verify Unix socket type: $path")
        if (mode and UNIX_SOCKET_MASK != UNIX_SOCKET_TYPE || attrs.fileKey() == null) {
            throw IOException("Control path is not a verifiable Unix socket: $path")
        }
        return SocketNode(attrs.fileKey()!!)
    }

    fun sameNode(path: Path, expected: SocketNode): Boolean =
        runCatching { requireSocketNode(path).fileKey == expected.fileKey }.getOrDefault(false)

    fun deleteIfSame(path: Path, expected: SocketNode) {
        if (!sameNode(path, expected)) throw IOException("Control socket changed while cleaning: $path")
        Files.deleteIfExists(path)
    }

    private const val UNIX_SOCKET_MASK = 0xF000
    private const val UNIX_SOCKET_TYPE = 0xC000
}
