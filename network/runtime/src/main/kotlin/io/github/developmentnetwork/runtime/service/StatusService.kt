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
        if (request.host.isBlank() || '\n' in request.host || '\r' in request.host) {
            output.println("network status: host must be a non-blank single line")
            return 1
        }
        if (request.timeout.isNegative || request.timeout.isZero) {
            output.println("network status: timeout must be positive")
            return 1
        }
        val proxyPort = try {
            request.proxyPort ?: readProxyPort() ?: 25565
        } catch (error: Exception) {
            output.println("network status: ${error.message ?: "invalid proxy configuration"}")
            return 1
        }
        if (proxyPort !in 1..65535) {
            output.println("network status: invalid proxy port $proxyPort")
            return 1
        }
        if (request.lobbyPort !in 1..65535) {
            output.println("network status: invalid lobby port ${request.lobbyPort}")
            return 1
        }

        val registrations = try {
            registry.readRegistrations()
        } catch (error: Exception) {
            output.println("network status: ${error.message ?: "invalid backend registry"}")
            return 1
        }
        val endpointPorts = listOf(proxyPort, request.lobbyPort) + registrations.map { it.port }
        if (endpointPorts.any { it !in 1..65535 } || endpointPorts.toSet().size != endpointPorts.size) {
            output.println("network status: endpoint port collision or invalid port")
            return 1
        }

        val endpoints = buildList {
            add("proxy" to proxyPort)
            add("lobby" to request.lobbyPort)
            registrations.forEach { add(it.name.value to it.port) }
        }
        var failed = false
        endpoints.forEach { (name, port) ->
            val status = try {
                probe.probe(request.host, port, request.timeout)
            } catch (error: Exception) {
                ServerStatus(reachable = false, error = error.message ?: error::class.simpleName)
            }
            output.println(format(name, request.host, port, status))
            if (!status.reachable) failed = true
        }
        return if (failed) 1 else 0
    }

    private fun readProxyPort(): Int? {
        val content = AtomicFiles.readIfExists(layout.velocityConfig) ?: return null
        val value = Regex("^bind\\s*=\\s*\\\"([^\"]+)\\\"", RegexOption.MULTILINE)
            .find(content)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("velocity.toml has no bind endpoint")
        val rawPort = value.substringAfterLast(':', missingDelimiterValue = "")
        return rawPort.toIntOrNull() ?: throw IllegalArgumentException("velocity.toml has invalid bind endpoint")
    }

    private fun format(name: String, host: String, port: Int, status: ServerStatus): String = if (status.reachable) {
        "${name}: ${host}:${port} reachable version=${status.version ?: "unknown"} players=${status.playersOnline ?: "?"}/${status.playersMax ?: "?"} motd=${status.motd ?: ""}"
    } else {
        "${name}: ${host}:${port} unreachable error=${status.error ?: "unknown"}"
    }
}
