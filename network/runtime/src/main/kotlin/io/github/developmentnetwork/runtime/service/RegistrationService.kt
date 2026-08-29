package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.config.OfflinePreflight
import io.github.developmentnetwork.runtime.config.VelocityConfig
import io.github.developmentnetwork.runtime.config.VelocityConfigWriter
import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.model.BackendRegistration
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.process.ReadinessProbe
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Explicit request for attaching an already-running external Paper process. */
data class RegisterExternalRequest(
    val name: String,
    val port: Int,
    val owner: String,
    val serverDir: Path,
    val host: String = "localhost",
    val targetServer: String = "localhost",
    val controlTimeout: Duration = Duration.ofSeconds(5),
    val readinessTimeout: Duration = Duration.ofSeconds(5),
)

/** Registers metadata only: no external configuration or process is ever changed. */
class RegistrationService(
    private val layout: RuntimeLayout,
    private val registry: RegistryStore = RegistryStore(layout),
    private val preflight: OfflinePreflight = OfflinePreflight(),
    private val readiness: EndpointReadiness = EndpointReadiness { host, port, timeout -> ReadinessProbe().await(host, port, timeout) },
    private val velocityWriter: VelocityConfigWriter = VelocityConfigWriter(),
    private val controlClient: ControlClient = ControlClient(),
) {
    fun execute(request: RegisterExternalRequest): Int {
        val name = try { BackendName(request.name) } catch (error: Exception) { return fail(error) }
        return try {
            require(request.owner.isNotBlank() && '\n' !in request.owner && '\r' !in request.owner) { "External owner must be a single line" }
            require(request.port in 1024..65535) { "External backend port must be in 1024..65535: ${request.port}" }
            require(Files.isDirectory(request.serverDir)) { "External Paper directory does not exist: ${request.serverDir}" }
            require(Files.exists(layout.proxyReady)) { "Proxy controller is not ready" }
            checkProxyController(request.controlTimeout)
            val paperResult = preflight.verifyPaper(request.serverDir, external = true)
            require(paperResult.success) { paperResult.message }
            readiness.await(request.host, request.port, request.readinessTimeout)
            val previous = registry.readRegistration(name)
            val hadReady = Files.exists(layout.backend(name).ready)
            if (previous != null && (previous.owner != request.owner || previous.mode != OwnershipMode.EXTERNAL)) {
                error("Backend $name is already owned by ${previous.owner}")
            }
            try {
                withoutLobbyProcessMarker {
                    registry.register(BackendRegistration(name, request.port, request.owner, OwnershipMode.EXTERNAL, null))
                }
                Files.createDirectories(layout.runtimeDir)
                AtomicFiles.write(layout.backend(name).ready, "ready\n")
                regenerate(request.targetServer)
                requestReload(request.controlTimeout)
            } catch (error: Exception) {
                rollback(name, request.owner, previous, hadReady)
                throw error
            }
            0
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun checkProxyController(timeout: Duration) {
        require(Files.exists(layout.proxyControl)) { "Proxy controller control socket is unavailable" }
        require(Files.exists(io.github.developmentnetwork.runtime.controller.ControlServer.tokenPath(layout.proxyControl))) {
            "Proxy controller authentication token is unavailable"
        }
        require(Files.exists(io.github.developmentnetwork.runtime.controller.ControlServer.leasePath(layout.proxyControl))) {
            "Proxy controller lease is unavailable"
        }
    }

    private fun requestReload(timeout: Duration) {
        val token = Files.readString(io.github.developmentnetwork.runtime.controller.ControlServer.tokenPath(layout.proxyControl)).trim()
        val response = controlClient.request(layout.proxyControl, token, ControlCommand.Reload, timeout)
        require(response.ok) { response.message.ifBlank { "proxy reload was rejected" } }
    }

    private fun regenerate(targetServer: String) {
        val ports = registry.readNames().mapNotNull { name -> registry.readRegistration(name)?.let { name.value to it.port } }.toMap()
        velocityWriter.write(layout, VelocityConfig(targetServer = targetServer, backendPorts = ports))
    }

    private fun rollback(name: BackendName, owner: String, previous: BackendRegistration?, hadReady: Boolean) {
        runCatching { registry.unregister(name, owner) }
        if (previous != null) runCatching { registry.register(previous) }
        if (hadReady) AtomicFiles.write(layout.backend(name).ready, "ready\n") else Files.deleteIfExists(layout.backend(name).ready)
    }
    /**
     * RegistryStore predates the controller's lobby.pid marker and discovers any
     * *.pid file as a backend claim. Hide only that known infrastructure marker
     * during its locked transition, restoring the exact marker in all outcomes.
     */
    private fun <T> withoutLobbyProcessMarker(action: () -> T): T {
        val marker = layout.runtimeDir.resolve("lobby.pid")
        if (!Files.exists(marker)) return action()
        val hidden = layout.base.resolve(".lobby.pid-registration-${ProcessHandle.current().pid()}-${System.nanoTime()}")
        Files.move(marker, hidden)
        return try {
            action()
        } finally {
            if (Files.exists(hidden)) Files.move(hidden, marker)
        }
    }

    private fun fail(error: Exception): Int {
        System.err.println("external registration: ${error.message ?: error::class.simpleName}")
        return 1
    }
}

/** Injectable readiness seam shared by registration and controller lifecycle. */
fun interface EndpointReadiness {
    fun await(host: String, port: Int, timeout: Duration)
}
