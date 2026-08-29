package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.config.VelocityConfig
import io.github.developmentnetwork.runtime.config.VelocityConfigWriter
import io.github.developmentnetwork.runtime.controller.ControlClient
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.time.Duration

/** Explicit owner-scoped external unregister request. */
data class UnregisterExternalRequest(
    val name: String,
    val owner: String,
    val targetServer: String = "localhost",
    val controlTimeout: Duration = Duration.ofSeconds(5),
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
            require(request.owner.isNotBlank() && '\n' !in request.owner && '\r' !in request.owner) { "External owner must be a single line" }
            val current = registry.readRegistration(name) ?: error("Backend $name is not registered")
            require(current.mode == OwnershipMode.EXTERNAL) { "Backend $name is managed; its controller owns cleanup" }
            require(current.owner == request.owner) { "Backend $name is owned by ${current.owner}, not ${request.owner}" }
            registry.unregister(name, request.owner)
            try {
                regenerate(request.targetServer)
                requestReload(request.controlTimeout)
            } catch (error: Exception) {
                // Registry removal is authoritative; report reload failure without
                // touching the external server or restoring a stale claim.
                throw error
            }
            0
        } catch (error: Exception) {
            System.err.println("external unregistration: ${error.message ?: error::class.simpleName}")
            1
        }
    }

    private fun regenerate(targetServer: String) {
        val ports = registry.readNames().mapNotNull { name -> registry.readRegistration(name)?.let { name.value to it.port } }.toMap()
        velocityWriter.write(layout, VelocityConfig(targetServer = targetServer, backendPorts = ports))
    }

    private fun requestReload(timeout: Duration) {
        require(Files.exists(layout.proxyControl)) { "Proxy controller control socket is unavailable" }
        val token = Files.readString(io.github.developmentnetwork.runtime.controller.ControlServer.tokenPath(layout.proxyControl)).trim()
        val response = controlClient.request(layout.proxyControl, token, ControlCommand.Reload, timeout)
        require(response.ok) { response.message.ifBlank { "proxy reload was rejected" } }
    }
}
