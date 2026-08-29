package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlServer
import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.model.ProcessIdentity
import io.github.developmentnetwork.runtime.process.ProcessIdentityReader
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Stop request. An owner, when supplied, narrows fallback cleanup to that run. */
data class StopNetworkRequest(
    val owner: String? = null,
    val controlTimeout: Duration = Duration.ofSeconds(5),
    val shutdownTimeout: Duration = Duration.ofSeconds(30),
)

/** Ask the live controller first; fallback is identity-gated and never process-name based. */
class StopService(
    private val layout: RuntimeLayout,
    private val controlClient: ControlClient = ControlClient(),
    private val identityReader: ProcessIdentityReader = ProcessIdentityReader(),
    private val registry: RegistryStore = RegistryStore(layout),
) {
    fun execute(request: StopNetworkRequest = StopNetworkRequest()): Int {
        val requested = request.owner
        if (requestController(request)) {
            if (awaitLeaseGone(request.controlTimeout)) return 0
        }
        return if (fallback(request)) 0 else 1
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
        val deadline = System.nanoTime() + timeout.toNanos().coerceAtLeast(0)
        val lease = ControlServer.leasePath(layout.proxyControl)
        while (System.nanoTime() < deadline) {
            if (!Files.exists(lease)) return true
            try { Thread.sleep(25) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !Files.exists(lease)
    }

    private fun fallback(request: StopNetworkRequest): Boolean {
        val ownerState = AtomicFiles.readIfExists(layout.proxyOwner).orEmpty()
        val owner = readKey(ownerState, "owner") ?: return false
        request.owner?.let { require(it == owner) { "Controller owner mismatch" } }
        val childIdentity = readIdentity(ownerState, "child")
        if (childIdentity != null && !terminateVerified(childIdentity, request.shutdownTimeout)) return false
        val lobbyIdentity = readIdentity(ownerState, "lobby")
        if (lobbyIdentity != null && !terminateVerified(lobbyIdentity, request.shutdownTimeout)) return false

        registry.readNames().mapNotNull { name -> registry.readRegistration(name) }
            .filter { it.mode == OwnershipMode.MANAGED && it.owner == owner }
            .forEach { registration ->
                val identity = registration.process ?: return@forEach
                if (!terminateVerified(identity, request.shutdownTimeout)) return false
                runCatching { registry.unregister(registration.name, owner) }
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
        val handle = ProcessHandle.of(identity.pid).orElse(null) ?: return true
        if (!handle.isAlive) return true
        if (!handle.destroy()) return false
        val deadline = System.nanoTime() + timeout.toNanos().coerceAtLeast(0)
        while (handle.isAlive && System.nanoTime() < deadline) {
            try { Thread.sleep(25) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (!handle.isAlive) return true
        if (!identityReader.matches(identity) || !handle.destroyForcibly()) return false
        while (handle.isAlive && System.nanoTime() < deadline + Duration.ofSeconds(2).toNanos()) {
            try { Thread.sleep(25) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !handle.isAlive
    }

    private fun readIdentity(content: String, prefix: String): ProcessIdentity? {
        val pid = readKey(content, "${prefix}-pid")?.toLongOrNull() ?: return null
        val start = readKey(content, "${prefix}-start")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        val executable = readKey(content, "${prefix}-executable")?.let(Path::of)
        val cwd = readKey(content, "${prefix}-working-directory")?.let(Path::of)
        return ProcessIdentity(pid, start, executable, cwd)
    }

    private fun readKey(content: String, key: String): String? = content.lineSequence()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }
}
