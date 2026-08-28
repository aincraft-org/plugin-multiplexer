package io.github.developmentnetwork.runtime.config

import io.github.developmentnetwork.runtime.model.BackendNames
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.nio.file.Path

/** Inputs for the deterministic development-network Velocity configuration. */
data class VelocityConfig(
    val proxyPort: Int = 25565,
    val targetServer: String = "localhost",
    val onlineMode: Boolean = true,
    /** Optional explicit/persisted backend ports keyed by validated backend name text. */
    val backendPorts: Map<String, Int> = emptyMap(),
)

/** Writes the one canonical Velocity configuration used by boot and reload. */
class VelocityConfigWriter {
    fun write(layout: RuntimeLayout, config: VelocityConfig): Path {
        require(config.proxyPort in 0..65535) { "Proxy port must be in 0..65535: ${config.proxyPort}" }
        require(config.targetServer.isNotBlank() && '\n' !in config.targetServer && '\r' !in config.targetServer) {
            "Target server must be a non-blank single-line host"
        }

        val names = if (config.backendPorts.isEmpty()) {
            val persisted = AtomicFiles.readLinesIfExists(layout.registryFile).orEmpty()
            persisted.asSequence()
                .filter { it.isNotEmpty() }
                .map { BackendNames.validate(it.removeSuffix("\r")) }
                .distinct()
                .sortedBy { it.value }
                .toList()
        } else {
            config.backendPorts.keys.map(BackendNames::validate).distinct().sortedBy { it.value }
        }
        config.backendPorts.forEach { (raw, port) ->
            BackendNames.validate(raw)
            require(port in 1024..65535) { "Backend port must be in 1024..65535 for $raw: $port" }
        }

        val ports = names.mapIndexed { index, name ->
            val persisted = layout.backend(name).port
            val persistedPort = if (Files.exists(persisted)) {
                val rawPort = AtomicFiles.read(persisted).trim()
                require(rawPort.toIntOrNull() != null) { "Invalid persisted backend port for $name" }
                rawPort.toInt()
            } else {
                null
            }
            val port = config.backendPorts[name.value] ?: persistedPort ?: (30067 + index)
            require(port in 1024..65535) { "Backend port must be in 1024..65535 for $name: $port" }
            name.value to port
        }

        val secret = "dev-local-forwarding-secret-change-me"
        AtomicFiles.write(layout.forwardingSecret, "$secret\n")
        val forwardingPath = tomlString(layout.forwardingSecret.toAbsolutePath().toString())
        val target = tomlString(config.targetServer)
        val content = buildString {
            appendLine("config-version = \"2.8\"")
            appendLine("bind = \"0.0.0.0:${config.proxyPort}\"")
            appendLine("motd = \"<#09add3>dev-network\"")
            appendLine("show-max-players = 20")
            appendLine("online-mode = ${config.onlineMode}")
            appendLine("force-key-authentication = true")
            appendLine("prevent-client-proxy-connections = false")
            appendLine("player-info-forwarding-mode = \"modern\"")
            appendLine("forwarding-secret-file = \"$forwardingPath\"")
            appendLine("announce-forge = false")
            appendLine("kick-existing-players = false")
            appendLine("ping-passthrough = \"DISABLED\"")
            appendLine("sample-players-in-ping = false")
            appendLine("enable-player-address-logging = true")
            appendLine("auto-connect-upstreams = true")
            appendLine()
            appendLine("[servers]")
            appendLine("lobby = \"$target:30066\"")
            ports.forEach { (name, port) -> appendLine("$name = \"$target:$port\"") }
            append("try = [\"lobby\"")
            ports.forEach { (name, _) -> append(", \"$name\"") }
            appendLine("]")
            appendLine()
            appendLine("[forced-hosts]")
            appendLine()
            appendLine("[advanced]")
            appendLine("compression-threshold = 256")
            appendLine("compression-level = -1")
            appendLine("login-ratelimit = 3000")
            appendLine("connection-timeout = 5000")
            appendLine("read-timeout = 30000")
            appendLine("haproxy-protocol = false")
            appendLine("tcp-fast-open = false")
            appendLine("bungee-plugin-message-channel = true")
            appendLine("show-ping-requests = false")
            appendLine("failover-on-unexpected-server-disconnect = true")
            appendLine("announce-proxy-commands = true")
            appendLine("log-command-executions = false")
            appendLine("log-player-connections = true")
            appendLine("accepts-transfers = false")
            appendLine("enable-reuse-port = false")
            appendLine("command-rate-limit = 50")
            appendLine("forward-commands-if-rate-limited = true")
            appendLine("kick-after-rate-limited-commands = 0")
            appendLine("tab-complete-rate-limit = 10")
            appendLine("kick-after-rate-limited-tab-completes = 0")
            appendLine()
            appendLine("[query]")
            appendLine("enabled = false")
            appendLine("port = 25565")
            appendLine("map = \"dev-network\"")
            appendLine("show-plugins = false")
        }
        AtomicFiles.write(layout.velocityConfig, content)
        return layout.velocityConfig
    }

    private fun tomlString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
