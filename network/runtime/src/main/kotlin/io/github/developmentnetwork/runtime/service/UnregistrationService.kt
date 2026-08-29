package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.config.VelocityConfig
import io.github.developmentnetwork.runtime.config.VelocityConfigWriter
import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlServer
import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.model.BackendRegistration
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Explicit owner-scoped external unregister request. */
data class UnregisterExternalRequest(
    val name: String,
    val owner: String,
    val targetServer: String = "localhost",
    val controlTimeout: Duration = Duration.ofSeconds(5),
    val lobbyPort: Int = 30066,
)

/** Removes only matching external registry metadata; leaves Paper and its files untouched. */
class UnregistrationService(
    private val layout: RuntimeLayout,
    private val registry: RegistryStore = RegistryStore(layout),
    private val velocityWriter: VelocityConfigWriter = VelocityConfigWriter(),
    private val controlClient: ControlClient = ControlClient(),
) {
    fun execute(request: UnregisterExternalRequest): Int {
        return try {
            val name = BackendName(request.name)
            require(request.owner.isNotBlank() && '\n' !in request.owner && '\r' !in request.owner) {
                "External owner must be a single line"
            }
            require(Files.exists(layout.proxyReady)) { "Proxy controller is not ready" }
            checkProxyController()
            val current = registry.readRegistration(name) ?: error("Backend $name is not registered")
            require(current.mode == OwnershipMode.EXTERNAL) {
                "Backend $name is managed; its controller owns cleanup"
            }
            require(current.owner == request.owner) { "Backend $name is owned by ${current.owner}, not ${request.owner}" }

            val previousConfig = capture(layout.velocityConfig)
            val previousSecret = capture(layout.forwardingSecret)
            val previousReady = capture(layout.backend(name).ready)
            var generatedConfig: ByteArray? = null
            try {
                registry.withRegistrationTransition {
                    val lockedCurrent = readRegistration(name)
                        ?: error("Backend $name disappeared before unregister")
                    require(lockedCurrent == current) { "Backend $name changed during unregister" }
                    require(unregister(name, request.owner)) { "Backend $name is not registered" }
                    val config = effectiveVelocityConfig(request.targetServer, request.lobbyPort)
                    generatedConfig = capture(layout.velocityConfig)
                }
                requestReload(request.controlTimeout)
                0
            } catch (error: Exception) {
                rollback(name, current, previousReady, previousConfig, previousSecret, generatedConfig)
                throw error
            }
        } catch (error: Exception) {
            System.err.println("external unregistration: ${error.message ?: error::class.simpleName}")
            1
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
        return VelocityConfig(
            proxyPort = existing?.let { readInt(it, "bind") } ?: 25565,
            targetServer = if (requestTarget == "localhost") existing?.let(::readLobbyTarget) ?: requestTarget else requestTarget,
            onlineMode = existing?.let { readBoolean(it, "online-mode") } ?: false,
            lobbyPort = existing?.let(::readLobbyPort) ?: requestedLobbyPort,
        )
    }

    private fun rollback(
        name: BackendName,
        previous: BackendRegistration,
        previousReady: ByteArray?,
        previousConfig: ByteArray?,
        previousSecret: ByteArray?,
        generatedConfig: ByteArray?,
    ) {
        runCatching {
            registry.withRegistrationTransition {
                if (readRegistration(name) == null) {
                    register(previous)
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

}
