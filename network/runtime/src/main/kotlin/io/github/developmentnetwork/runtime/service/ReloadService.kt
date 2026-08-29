package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.config.VelocityConfig
import io.github.developmentnetwork.runtime.config.VelocityConfigWriter
import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.time.Duration

/** Reload request; repeated execution is intentionally idempotent. */
data class ReloadNetworkRequest(
    val targetServer: String = "localhost",
    val proxyPort: Int = 25565,
    val onlineMode: Boolean = true,
    val controlTimeout: Duration = Duration.ofSeconds(5),
)

class ReloadService(
    private val layout: RuntimeLayout,
    private val registry: RegistryStore = RegistryStore(layout),
    private val velocityWriter: VelocityConfigWriter = VelocityConfigWriter(),
    private val controlClient: ControlClient = ControlClient(),
) {
    fun execute(request: ReloadNetworkRequest = ReloadNetworkRequest()): Int {
        return try {
            require(Files.exists(layout.proxyControl)) { "Proxy controller control socket is unavailable" }
            val ports = registry.readNames().mapNotNull { name -> registry.readRegistration(name)?.let { name.value to it.port } }.toMap()
            velocityWriter.write(layout, VelocityConfig(request.proxyPort, request.targetServer, request.onlineMode, ports))
            val tokenPath = io.github.developmentnetwork.runtime.controller.ControlServer.tokenPath(layout.proxyControl)
            val token = Files.readString(tokenPath).trim()
            val response = controlClient.request(layout.proxyControl, token, ControlCommand.Reload, request.controlTimeout)
            require(response.ok) { response.message.ifBlank { "proxy reload was rejected" } }
            0
        } catch (error: Exception) {
            System.err.println("network reload: ${error.message ?: error::class.simpleName}")
            1
        }
    }
}
