package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.config.VelocityConfig
import io.github.developmentnetwork.runtime.config.VelocityConfigWriter
import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlServer
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Reload request; repeated execution is intentionally idempotent. */
data class ReloadNetworkRequest(
    val targetServer: String = "localhost",
    val proxyPort: Int = 25565,
    val onlineMode: Boolean = false,
    val controlTimeout: Duration = Duration.ofSeconds(5),
    val lobbyPort: Int = 30066,
)

class ReloadService(
    private val layout: RuntimeLayout,
    private val registry: RegistryStore = RegistryStore(layout),
    private val velocityWriter: VelocityConfigWriter = VelocityConfigWriter(),
    private val controlClient: ControlClient = ControlClient(),
) {
    fun execute(request: ReloadNetworkRequest = ReloadNetworkRequest()): Int {
        return try {
            require(Files.exists(layout.proxyReady)) { "Proxy controller is not ready" }
            checkProxyController()
            val previousConfig = capture(layout.velocityConfig)
            val previousSecret = capture(layout.forwardingSecret)
            var generatedConfig: ByteArray? = null
            try {
                registry.withRegistrationTransition {
                    val config = effectiveVelocityConfig(request)
                    velocityWriter.write(
                        layout,
                        config.copy(backendPorts = readRegistrations().associate { it.name.value to it.port }),
                    )
                    generatedConfig = capture(layout.velocityConfig)
                }
                requestReload(request.controlTimeout)
                0
            } catch (error: Exception) {
                if (generatedConfig == null || capture(layout.velocityConfig)?.contentEquals(generatedConfig) == true) {
                    restore(layout.velocityConfig, previousConfig)
                    restore(layout.forwardingSecret, previousSecret)
                }
                throw error
            }
        } catch (error: Exception) {
            System.err.println("network reload: ${error.message ?: error::class.simpleName}")
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

    private fun effectiveVelocityConfig(request: ReloadNetworkRequest): VelocityConfig {
        val existing = capture(layout.velocityConfig)?.toString(Charsets.UTF_8)
        return VelocityConfig(
            // A bound port in the active config is authoritative. A request value of 0
            // means allocate only for initial infrastructure startup, never on reload.
            proxyPort = existing?.let { readInt(it, "bind") } ?: request.proxyPort,
            targetServer = if (request.targetServer == "localhost") existing?.let(::readLobbyTarget) ?: request.targetServer else request.targetServer,
            onlineMode = existing?.let { readBoolean(it, "online-mode") } ?: request.onlineMode,
            lobbyPort = existing?.let(::readLobbyPort) ?: request.lobbyPort,
        )
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
