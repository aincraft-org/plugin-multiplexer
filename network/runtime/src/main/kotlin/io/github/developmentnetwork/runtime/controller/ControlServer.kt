package io.github.developmentnetwork.runtime.controller

import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
        val address = socket.toAbsolutePath().normalize()
        val parent = address.parent ?: error("Control socket must have a parent directory: $socket")
        Files.createDirectories(parent)
        prepareSocket(address)

        val current = currentLease(token)
        val channel = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        var bound = false
        try {
            channel.bind(java.net.UnixDomainSocketAddress.of(address))
            bound = true
            setOwnerOnly(address)
            writeSecure(tokenPath(address), "$token\n")
            writeSecure(leasePath(address), current.encode())
        } catch (error: Throwable) {
            runCatching { channel.close() }
            if (bound) Files.deleteIfExists(address)
            throw error
        }

        val running = AtomicBoolean(true)
        val listener = thread(
            start = true,
            isDaemon = true,
            name = "runtime-control-${address.fileName}",
        ) {
            while (running.get()) {
                try {
                    channel.accept().use { client ->
                        serveRequest(client, address, token, handler)
                    }
                } catch (_: java.nio.channels.AsynchronousCloseException) {
                    break
                } catch (_: java.nio.channels.ClosedByInterruptException) {
                    break
                } catch (_: IOException) {
                    if (!running.get()) break
                }
            }
        }

        return Closeable {
            if (!running.compareAndSet(true, false)) return@Closeable
            runCatching { channel.close() }
            listener.interrupt()
            runCatching { listener.join(CLOSE_JOIN_MILLIS) }
            removeOwnedState(address, current)
        }
    }

    private fun serveRequest(
        client: java.nio.channels.SocketChannel,
        socket: Path,
        expectedToken: String,
        handler: (ControlCommand) -> ControlResponse,
    ) {
        val reader = Channels.newReader(client, StandardCharsets.UTF_8.newDecoder(), -1)
            .buffered()
        val writer = Channels.newWriter(client, StandardCharsets.UTF_8.newEncoder(), -1)
            .buffered()
        val suppliedToken = reader.readLine() ?: return
        val commandText = reader.readLine() ?: return
        if (!isAuthenticated(socket, expectedToken, suppliedToken)) {
            writeResponse(writer, ControlResponse.failure(ControlWire.AUTHENTICATION_FAILED))
            return
        }
        val command = ControlWire.decodeCommand(commandText)
        if (command == null) {
            writeResponse(writer, ControlResponse.failure("unknown control command"))
            return
        }
        val response = runCatching { handler(command) }
            .getOrElse { error -> ControlResponse.failure(error.message ?: "control handler failed") }
        writeResponse(writer, response)
    }

    private fun writeResponse(writer: BufferedWriter, response: ControlResponse) {
        writer.write(ControlWire.encodeResponse(response))
        writer.flush()
    }

    private fun isAuthenticated(socket: Path, expectedToken: String, suppliedToken: String): Boolean {
        if (!constantTimeEquals(expectedToken, suppliedToken)) return false
        val persisted = runCatching { Files.readString(tokenPath(socket), StandardCharsets.UTF_8).trimEnd('\r', '\n') }
            .getOrNull() ?: return false
        if (!constantTimeEquals(expectedToken, persisted)) return false
        val lease = readLease(leasePath(socket)) ?: return false
        if (!constantTimeEquals(expectedToken, lease.token)) return false
        if (!lease.isLive()) return false
        return true
    }

    private fun prepareSocket(socket: Path) {
        if (!Files.exists(socket, NOFOLLOW_LINKS)) return
        val oldLease = readLease(leasePath(socket))
        if (oldLease != null) {
            if (oldLease.isLive()) {
                throw IOException("Control socket is owned by a live controller: $socket")
            }
            // We proved the recorded owner cannot still be alive before removing stale state.
            removeStaleState(socket)
            return
        }
        // Never treat an arbitrary regular file or symlink as a stale socket. For an
        // unleased Unix socket, a refused connection proves no listener can own the path.
        if (isUnixSocket(socket) && noLiveSocketListener(socket)) {
            removeStaleState(socket)
            return
        }
        throw IOException("Control socket exists without a verifiable controller lease: $socket")
    }

    private fun isUnixSocket(socket: Path): Boolean =
        runCatching {
            val mode = Files.getAttribute(socket, "unix:mode", NOFOLLOW_LINKS) as? Int ?: return@runCatching false
            mode and UNIX_SOCKET_MASK == UNIX_SOCKET_TYPE
        }.getOrDefault(false)

    private fun noLiveSocketListener(socket: Path): Boolean =
        runCatching {
            java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(java.net.UnixDomainSocketAddress.of(socket))
            }
            false
        }.getOrDefault(true)

    private fun removeStaleState(socket: Path) {
        Files.deleteIfExists(socket)
        Files.deleteIfExists(tokenPath(socket))
        Files.deleteIfExists(leasePath(socket))
    }

    private fun removeOwnedState(socket: Path, lease: Lease) {
        val currentLease = readLease(leasePath(socket)) ?: return
        if (currentLease != lease || !constantTimeEquals(currentLease.token, lease.token)) return
        Files.deleteIfExists(socket)
        Files.deleteIfExists(tokenPath(socket))
        Files.deleteIfExists(leasePath(socket))
    }

    private fun currentLease(token: String): Lease {
        val process = ProcessHandle.current()
        val start = process.info().startInstant().orElse(null)
            ?: throw IllegalStateException("Current controller has no process start identity")
        return Lease(process.pid(), start, token)
    }

    private fun readLease(path: Path): Lease? {
        val lines = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val values = lines.mapNotNull { line -> line.substringBefore('=').takeIf { it != line }?.let { it to line.substringAfter('=') } }
            .toMap()
        val pid = values["pid"]?.toLongOrNull() ?: return null
        val start = runCatching { Instant.parse(values["start"] ?: return null) }.getOrNull() ?: return null
        val token = values["token"] ?: return null
        return Lease(pid, start, token)
    }

    private fun writeSecure(path: Path, content: String) {
        val parent = path.parent ?: error("State file must have a parent: $path")
        Files.createDirectories(parent)
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

    private fun setOwnerOnly(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }
            .onFailure { error -> if (error !is UnsupportedOperationException) throw error }
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(
            first.toByteArray(StandardCharsets.UTF_8),
            second.toByteArray(StandardCharsets.UTF_8),
        )

    private data class Lease(val pid: Long, val start: Instant, val token: String) {
        fun encode(): String = "pid=$pid\nstart=$start\ntoken=$token\n"
        fun isLive(): Boolean {
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            return handle.isAlive && handle.info().startInstant().orElse(null) == start
        }
    }

    companion object {
        private val RANDOM = SecureRandom()
        private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val UNIX_SOCKET_MASK = 0xF000
        private const val UNIX_SOCKET_TYPE = 0xC000

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
