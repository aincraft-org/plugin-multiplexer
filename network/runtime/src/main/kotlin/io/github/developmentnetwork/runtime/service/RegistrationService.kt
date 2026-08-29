package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.config.OfflinePreflight
import io.github.developmentnetwork.runtime.config.VelocityConfig
import io.github.developmentnetwork.runtime.config.VelocityConfigWriter
import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlServer
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
    val lobbyPort: Int = 30066,
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
            require(request.owner.isNotBlank() && '\n' !in request.owner && '\r' !in request.owner) {
                "External owner must be a single line"
            }
            require(request.port in 1024..65535) { "External backend port must be in 1024..65535: ${request.port}" }
            require(Files.isDirectory(request.serverDir)) { "External Paper directory does not exist: ${request.serverDir}" }
            require(Files.exists(layout.proxyReady)) { "Proxy controller is not ready" }
            checkProxyController()
            val paperResult = preflight.verifyPaper(request.serverDir, external = true)
            require(paperResult.success) { paperResult.message }
            readiness.await(request.host, request.port, request.readinessTimeout)

            val previousConfig = capture(layout.velocityConfig)
            val previousSecret = capture(layout.forwardingSecret)
            val previousReady = capture(layout.backend(name).ready)
            val previous = registry.readRegistration(name)
            if (previous != null && (previous.owner != request.owner || previous.mode != OwnershipMode.EXTERNAL)) {
                error("Backend $name is already owned by ${previous.owner}")
            }
            var candidate: BackendRegistration? = null
            var generatedConfig: ByteArray? = null
            try {
                registry.withRegistrationTransition {
                    val config = effectiveVelocityConfig(request.targetServer, request.lobbyPort)
                    require(request.port != config.proxyPort && request.port != config.lobbyPort) {
                        "External backend port ${request.port} collides with the active proxy/lobby port"
                    }
                    val registration = BackendRegistration(name, request.port, request.owner, OwnershipMode.EXTERNAL, null)
                    candidate = registration
                    register(registration)
                    Files.createDirectories(layout.runtimeDir)
                    velocityWriter.write(layout, config.copy(backendPorts = readRegistrations().associate { it.name.value to it.port }))
                    generatedConfig = capture(layout.velocityConfig)
                }
                requestReload(request.controlTimeout)
                0
            } catch (error: Exception) {
                candidate?.let {
                    rollback(name, request.owner, it, previous, previousReady, previousConfig, previousSecret, generatedConfig)
                }
                throw error
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun checkProxyController() {
        require(Files.exists(layout.proxyControl)) { "Proxy controller control socket is unavailable" }
        require(Files.exists(ControlServer.tokenPath(layout.proxyControl))) {
            "Proxy controller authentication token is unavailable"
        }
        require(Files.exists(ControlServer.leasePath(layout.proxyControl))) {
            "Proxy controller lease is unavailable"
        }
    }

    private fun requestReload(timeout: Duration) {
        val token = Files.readString(ControlServer.tokenPath(layout.proxyControl)).trim()
        val response = controlClient.request(layout.proxyControl, token, ControlCommand.Reload, timeout)
        require(response.ok) { response.message.ifBlank { "proxy reload was rejected" } }
    }

    private fun effectiveVelocityConfig(requestTarget: String, requestedLobbyPort: Int): VelocityConfig {
        val existing = capture(layout.velocityConfig)?.toString(Charsets.UTF_8)
            ?: error("Active proxy configuration is unavailable at ${layout.velocityConfig}")
        val existingPort = readInt(existing, "bind")
            ?: error("Active proxy configuration has no valid bind port")
        val existingTarget = readLobbyTarget(existing)
            ?: error("Active proxy configuration has no valid lobby target")
        val existingOnline = readBoolean(existing, "online-mode")
            ?: error("Active proxy configuration has no valid online-mode setting")
        val existingLobbyPort = readLobbyPort(existing)
            ?: error("Active proxy configuration has no valid lobby port")
        return VelocityConfig(
            proxyPort = existingPort,
            targetServer = if (requestTarget == "localhost") existingTarget else requestTarget,
            onlineMode = existingOnline,
            lobbyPort = existingLobbyPort,
        )
    }

    private fun rollback(
        name: BackendName,
        owner: String,
        candidate: BackendRegistration,
        previous: BackendRegistration?,
        previousReady: ByteArray?,
        previousConfig: ByteArray?,
        previousSecret: ByteArray?,
        generatedConfig: ByteArray?,
    ) {
        runCatching {
            registry.withRegistrationTransition {
                val current = readRegistration(name)
                if (current == candidate) {
                    if (previous == null) unregister(name, owner) else register(previous)
                    restore(layout.backend(name).ready, previousReady)
                }
            }
        }
        if (generatedConfig == null || capture(layout.velocityConfig)?.contentEquals(generatedConfig) == true) {
            restore(layout.velocityConfig, previousConfig)
            restore(layout.forwardingSecret, previousSecret)
        }
    }

    private fun capture(path: Path): ByteArray? =
        if (Files.exists(path)) Files.readAllBytes(path) else null

    private fun restore(path: Path, content: ByteArray?) {
        if (content == null) Files.deleteIfExists(path)
        else AtomicFiles.write(path, content.toString(Charsets.UTF_8))
    }

    private fun readInt(content: String, key: String): Int? =
        Regex("^$key\\s*=\\s*\\\"[^:]+:(\\d+)\\\"", RegexOption.MULTILINE)
            .find(content)?.groupValues?.get(1)?.toIntOrNull()

    private fun readBoolean(content: String, key: String): Boolean? =
        Regex("^$key\\s*=\\s*(true|false)\\s*$", RegexOption.MULTILINE)
            .find(content)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun readLobbyTarget(content: String): String? =
        Regex("^lobby\\s*=\\s*\\\"([^:]+):\\d+\\\"", RegexOption.MULTILINE)
            .find(content)?.groupValues?.get(1)

    private fun readLobbyPort(content: String): Int? =
        Regex("^lobby\\s*=\\s*\\\"[^:]+:(\\d+)\\\"", RegexOption.MULTILINE)
            .find(content)?.groupValues?.get(1)?.toIntOrNull()
    private fun fail(error: Exception): Int {
        System.err.println("external registration: ${error.message ?: error::class.simpleName}")
        return 1
    }
}

/** Injectable readiness seam shared by registration and controller lifecycle. */
fun interface EndpointReadiness {
    fun await(host: String, port: Int, timeout: Duration)
}
