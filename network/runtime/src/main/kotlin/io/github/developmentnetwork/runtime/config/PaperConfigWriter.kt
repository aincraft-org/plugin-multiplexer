package io.github.developmentnetwork.runtime.config

import io.github.developmentnetwork.runtime.state.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path

/** Inputs for a managed Paper server's generated configuration. */
data class PaperConfig(
    val port: Int,
    val forwardingSecret: String = SharedForwardingSecret.VALUE,
    val levelName: String = "world",
    val motd: String = "dev-network lobby",
)

/** Generates only files owned by a managed Paper work directory. */
class PaperConfigWriter {
    fun writeManaged(workDir: Path, config: PaperConfig) {
        require(config.forwardingSecret == SharedForwardingSecret.VALUE) {
            "Paper forwarding secret must be the shared development secret"
        }
        require(config.port in 1024..65535) { "Paper port must be in 1024..65535: ${config.port}" }
        require(config.forwardingSecret.isNotBlank() && '\n' !in config.forwardingSecret && '\r' !in config.forwardingSecret) {
            "Forwarding secret must be a non-blank single line"
        }
        require('\n' !in config.levelName && '\r' !in config.levelName) { "Level name must be a single line" }
        require('\n' !in config.motd && '\r' !in config.motd) { "MOTD must be a single line" }
        Files.createDirectories(workDir)
        Files.createDirectories(workDir.resolve("config"))

        AtomicFiles.write(
            workDir.resolve("server.properties"),
            buildString {
                appendLine("server-port=${config.port}")
                appendLine("online-mode=false")
                appendLine("level-name=${config.levelName}")
                appendLine("motd=${config.motd}")
            },
        )
        AtomicFiles.write(
            workDir.resolve("config/paper-global.yml"),
            """proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "${yamlString(config.forwardingSecret)}"
""",
        )
        AtomicFiles.write(workDir.resolve("eula.txt"), "eula=true\n")
        AtomicFiles.write(
            workDir.resolve("spigot.yml"),
            """settings:
  bungeecord: false
""",
        )
    }

    private fun yamlString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
