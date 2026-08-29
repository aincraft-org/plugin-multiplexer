package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlServer
import io.github.developmentnetwork.runtime.model.ProcessIdentity
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.process.ProcessIdentityReader
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Stop request. An owner, when supplied, narrows fallback cleanup to that run. */
data class StopNetworkRequest(
    val owner: String? = null,
    val controlTimeout: Duration = Duration.ofSeconds(5),
    val shutdownTimeout: Duration = Duration.ofSeconds(30),
    val managedOnly: Boolean = false,
)

/** Ask the live controller first; fallback is identity-gated and never process-name based. */
class StopService(
    private val layout: RuntimeLayout,
    private val controlClient: ControlClient = ControlClient(),
    private val identityReader: ProcessIdentityReader = ProcessIdentityReader(),
    private val registry: RegistryStore = RegistryStore(layout),
) {
    fun execute(request: StopNetworkRequest = StopNetworkRequest()): Int {
        if (request.managedOnly) return if (fallbackManaged(request)) 0 else 1
        val controllerRequested = requestController(request)
        if (controllerRequested) {
            // A successful request is not completion. Never signal a second process while
            // the serving controller's lease is still present or cannot be classified.
            return if (awaitLeaseGone(request.controlTimeout)) 0 else 1
        }
        return when (leaseState()) {
            LeaseState.LIVE, LeaseState.UNKNOWN -> 1
            LeaseState.ABSENT, LeaseState.DEAD -> if (fallback(request)) 0 else 1
        }
    }

    private fun requestController(request: StopNetworkRequest): Boolean {
        return try {
            val socket = layout.proxyControl
            val tokenPath = ControlServer.tokenPath(socket)
            if (!Files.exists(socket) || !Files.exists(tokenPath)) return false
            val token = Files.readString(tokenPath).trim()
            val response = controlClient.request(socket, token, ControlCommand.Shutdown, request.controlTimeout)
            response.ok
        } catch (_: Exception) {
            false
        }
    }

    private fun awaitLeaseGone(timeout: Duration): Boolean {
        val deadline = deadline(timeout)
        val lease = ControlServer.leasePath(layout.proxyControl)
        while (System.nanoTime() < deadline) {
            if (!Files.exists(lease, NOFOLLOW_LINKS)) return true
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !Files.exists(lease, NOFOLLOW_LINKS)
    }

    /** Fallback must classify a persisted lease before it can inspect owner state. */
    private fun leaseState(): LeaseState {
        val lease = ControlServer.leasePath(layout.proxyControl)
        if (!Files.exists(lease, NOFOLLOW_LINKS)) return LeaseState.ABSENT
        if (Files.isSymbolicLink(lease) || !Files.isRegularFile(lease, NOFOLLOW_LINKS)) return LeaseState.UNKNOWN
        val content = runCatching { Files.readString(lease) }.getOrElse { return LeaseState.UNKNOWN }
        val values = content.lineSequence().map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }.toList()
        if (values.size != 3) return LeaseState.UNKNOWN
        val parsed = mutableMapOf<String, String>()
        for (line in values) {
            val separator = line.indexOf('=')
            if (separator <= 0 || parsed.put(line.substring(0, separator), line.substring(separator + 1)) != null) {
                return LeaseState.UNKNOWN
            }
        }
        if (parsed.keys != setOf("pid", "start", "token")) return LeaseState.UNKNOWN
        val pid = parsed["pid"]?.toLongOrNull() ?: return LeaseState.UNKNOWN
        val start = runCatching { Instant.parse(parsed["start"] ?: return LeaseState.UNKNOWN) }.getOrElse { return LeaseState.UNKNOWN }
        if (parsed["token"].isNullOrBlank()) return LeaseState.UNKNOWN
        val handle = try { ProcessHandle.of(pid).orElse(null) } catch (_: Exception) { return LeaseState.UNKNOWN }
            ?: return LeaseState.DEAD
        if (!handle.isAlive) return LeaseState.DEAD
        val actualStart = runCatching { handle.info().startInstant().orElse(null) }.getOrNull()
            ?: return LeaseState.UNKNOWN
        return if (actualStart == start) LeaseState.LIVE else LeaseState.DEAD
    }

    private fun fallbackManaged(request: StopNetworkRequest): Boolean {
        val owner = request.owner?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            registry.withRegistrationTransition {
                val managed = readRegistrations().filter { it.mode == OwnershipMode.MANAGED && it.owner == owner }
                val identities = managed.mapNotNull { it.process }
                if (managed.isEmpty() || identities.size != managed.size || identities.any { !identityReader.matches(it) }) {
                    false
                } else {
                    managed.all { registration ->
                        val identity = registration.process ?: return@all false
                        terminateVerified(identity, request.shutdownTimeout) &&
                            unregister(registration.name, owner)
                    }
                }
            }
        }.getOrDefault(false)
    }

    private fun fallback(request: StopNetworkRequest): Boolean {
        val ownerState = AtomicFiles.readIfExists(layout.proxyOwner) ?: return false
        val owner = readKey(ownerState, "owner") ?: return false
        if (request.owner == null || request.owner != owner) return false

        // Parse and verify every identity before sending any signal. This prevents a
        // later indeterminate record from leaving an earlier process silently cleaned.
        val childIdentity = readRequiredIdentity(ownerState, "child") ?: return false
        val lobbyIdentity = readRequiredIdentity(ownerState, "lobby") ?: return false
        val managed = try {
            registry.readRegistrations().filter { it.mode == OwnershipMode.MANAGED && it.owner == owner }
        } catch (_: Exception) {
            return false
        }
        val managedIdentities = managed.map { it to (it.process ?: return false) }
        val identities = listOf(childIdentity, lobbyIdentity) + managedIdentities.map { it.second }
        if (identities.any { !identityReader.matches(it) }) return false

        // Preserve all state as evidence if any termination fails. Only after every
        // owned identity has been observed dead are lease markers removed.
        if (!terminateVerified(childIdentity, request.shutdownTimeout)) return false
        if (!terminateVerified(lobbyIdentity, request.shutdownTimeout)) return false
        for ((registration, identity) in managedIdentities) {
            if (!terminateVerified(identity, request.shutdownTimeout)) return false
            if (!runCatching { registry.unregister(registration.name, owner) }.getOrDefault(false)) return false
        }
        Files.deleteIfExists(layout.proxyReady)
        Files.deleteIfExists(layout.proxyPid)
        Files.deleteIfExists(layout.proxyOwner)
        Files.deleteIfExists(layout.runtimeDir.resolve("lobby.ready"))
        Files.deleteIfExists(layout.runtimeDir.resolve("lobby.pid"))
        return true
    }

    private fun terminateVerified(identity: ProcessIdentity, timeout: Duration): Boolean {
        if (!identityReader.matches(identity)) return false
        val handle = try { ProcessHandle.of(identity.pid).orElse(null) } catch (_: Exception) { return false }
            ?: return false
        if (!handle.isAlive) return false
        if (!runCatching { handle.destroy() }.getOrDefault(false)) return false
        val deadline = deadline(timeout)
        while (handle.isAlive && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (!handle.isAlive) return true
        if (!identityReader.matches(identity)) return false
        if (!runCatching { handle.destroyForcibly() }.getOrDefault(false)) return false
        val forceDeadline = deadline(Duration.ofSeconds(2))
        while (handle.isAlive && System.nanoTime() < forceDeadline) {
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !handle.isAlive
    }

    private fun readRequiredIdentity(content: String, prefix: String): ProcessIdentity? {
        val fields = listOf("pid", "start", "executable", "working-directory")
        val present = fields.map { it to readKey(content, "$prefix-$it") }.toMap()
        if (present.values.all { it == null } || present.values.any { it == null }) return null
        val pid = present.getValue("pid")!!.toLongOrNull() ?: return null
        val start = runCatching { Instant.parse(present.getValue("start")!!) }.getOrNull() ?: return null
        val executable = runCatching { Path.of(present.getValue("executable")!!).toAbsolutePath().normalize() }.getOrNull() ?: return null
        val cwd = runCatching { Path.of(present.getValue("working-directory")!!).toAbsolutePath().normalize() }.getOrNull() ?: return null
        return runCatching { ProcessIdentity(pid, start, executable, cwd) }.getOrNull()
    }

    private fun readKey(content: String, key: String): String? = content.lineSequence()
        .map { it.removeSuffix("\r") }
        .filter { it.startsWith("$key=") }
        .map { it.substringAfter('=') }
        .lastOrNull()
        ?.takeIf { it.isNotBlank() }

    private fun deadline(timeout: Duration): Long {
        val nanos = runCatching { timeout.toNanos() }.getOrDefault(Long.MAX_VALUE).coerceAtLeast(0)
        val now = System.nanoTime()
        return if (nanos > Long.MAX_VALUE - now) Long.MAX_VALUE else now + nanos
    }

    private enum class LeaseState { ABSENT, LIVE, DEAD, UNKNOWN }
}
