package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import io.github.developmentnetwork.runtime.status.MinecraftStatusProbe
import io.github.developmentnetwork.runtime.status.ServerStatus
import java.io.PrintStream
import java.time.Duration

/** Read-only network endpoint status request. */
data class NetworkStatusRequest(
    val host: String = "localhost",
    val proxyPort: Int? = null,
    val lobbyPort: Int = 30066,
    val timeout: Duration = Duration.ofSeconds(3),
)

fun interface StatusEndpointProbe {
    fun probe(host: String, port: Int, timeout: Duration): ServerStatus
}

/** Probes proxy, lobby, and every registered backend without changing runtime state. */
class StatusService(
    private val layout: RuntimeLayout,
    private val registry: RegistryStore = RegistryStore(layout),
    private val probe: StatusEndpointProbe = StatusEndpointProbe { host, port, timeout -> MinecraftStatusProbe().probe(host, port, timeout) },
    private val output: PrintStream = System.out,
) {
    fun execute(request: NetworkStatusRequest = NetworkStatusRequest()): Int {
        return try {
            require(request.host.isNotBlank()) { "Status host must not be blank" }
            require(request.timeout.toMillis() > 0) { "Status timeout must be positive" }
            val proxyPort = request.proxyPort ?: readProxyPort() ?: 25565
            val endpoints = buildList {
                add("proxy" to proxyPort)
                add("lobby" to request.lobbyPort)
                registry.readNames().mapNotNull { name -> registry.readRegistration(name)?.let { add(name.value to it.port) } }
            }
            var failed = false
            endpoints.forEach { (name, port) ->
                val status = probe.probe(request.host, port, request.timeout)
                output.println(format(name, request.host, port, status))
                if (!status.reachable) failed = true
            }
            if (failed) 1 else 0
        } catch (error: Exception) {
            output.println("network status: ${error.message ?: error::class.simpleName}")
            1
        }
    }

    private fun readProxyPort(): Int? {
        val content = AtomicFiles.readIfExists(layout.velocityConfig) ?: return null
        val match = Regex("^bind\\s*=\\s*\\\"[^:]+:(\\d+)\\\"", RegexOption.MULTILINE).find(content) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun format(name: String, host: String, port: Int, status: ServerStatus): String = if (status.reachable) {
        "${name}: ${host}:${port} reachable version=${status.version ?: "unknown"} players=${status.playersOnline ?: "?"}/${status.playersMax ?: "?"} motd=${status.motd ?: ""}"
    } else {
        "${name}: ${host}:${port} unreachable error=${status.error ?: "unknown"}"
    }
}
