package io.github.developmentnetwork

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DocumentationContractTest {
    @Test
    fun `documentation describes the Gradle runtime contract without shell harness references`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val files = buildList {
            add(root.resolve("README.md"))
            add(root.resolve("SKILL.md"))
            root.resolve("AGENTS.md").takeIf { it.exists() }?.let(::add)
            val workflows = root.resolve(".github/workflows")
            if (Files.isDirectory(workflows)) {
                Files.walk(workflows).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter { it.toString().endsWith(".yml") || it.toString().endsWith(".yaml") }
                        .sorted()
                        .forEach(::add)
                }
            }
        }
        val corpus = files.joinToString("\n") { path ->
            assertTrue(path.exists(), "missing contract file: $path")
            path.readText()
        }
        val lower = corpus
            .replace("${'$'}{java.home}/bin/java", "<java-launcher>")
            .lowercase()

        listOf(
            "bin/",
            "boot-backend.sh",
            "boot-external.sh",
            "boot-lobby.sh",
            "boot-proxy.sh",
            "dev-network-status.sh",
            "dev-network.sh",
            "fetch-jar.sh",
            "install-lobby-map.sh",
            "register-backend.sh",
            "reload-network.sh",
            "restart-backend.sh",
            "stop-dev-network.sh",
            "test-lobby-map.sh",
            "test-network.sh",
            "unregister-backend.sh",
            "velocity-toml.sh",
            "write-ops.sh",
            "DEV_NETWORK_BIN",
            "DEV_NETWORK_DIR",
            "shellcheck",
            "bash -n",
            "shell harness",
        ).forEach { forbidden ->
            assertTrue(!lower.contains(forbidden.lowercase()), "removed shell reference remains: $forbidden")
        }

        listOf(
            "runProxy",
            "registerBackend",
            "unregisterBackend",
            "runBackend",
            "runNetwork",
            "stopNetwork",
            "reloadNetwork",
            "restartBackend",
            "networkStatus",
        ).forEach { task ->
            assertTrue(corpus.contains(task), "missing Gradle task contract: $task")
        }
        assertTrue(lower.contains("includebuild("), "missing consumer include-build instructions")
        assertTrue(lower.contains("network/"), "include-build must target the network build")

        val normalizedExamples = corpus.replace("\\", "").replace(Regex("\\s+"), " ")
        assertTrue(
            normalizedExamples.contains("./gradlew runProxy -PnetworkLobbyMapUrl=") &&
                normalizedExamples.contains("-PnetworkLobbyMapSha256="),
            "missing static map Gradle invocation with checksum",
        )
        assertTrue(
            normalizedExamples.contains("./gradlew runProxy -PnetworkLobbyMapRandomUrl="),
            "missing random map Gradle invocation",
        )

        listOf(
            "runtime.jar",
            "content-addressed",
            "<java-launcher>",
            "one infrastructure owner",
            "external servers remain untouched",
            "never starts, stops, or deploys to paper",
            "online-mode=false",
            "modern forwarding",
            "preflight",
            "forwarding.secret",
            "register.lock",
            "lobby map",
            "level.dat",
            "immutable",
            "random mode",
            "sha-256",
            "atomic",
            "symlink",
            "path traversal",
            "status",
            "25565",
            "30066",
            "30067",
            "networkBase",
            "networkBackend",
            "networkBackendPort",
            "networkProxyPort",
            "networkJarTask",
            "networkDevUsers",
            "networkOnlineMode",
            "networkRegistrationOwner",
            "networkTargetServer",
            "networkLobbyMapUrl",
            "networkLobbyMapSha256",
            "networkLobbyMapRandomUrl",
            "Velocity 4.1.1",
            "Paper 26.2",
        ).forEach { phrase ->
            assertTrue(lower.contains(phrase.lowercase()), "missing retained contract: $phrase")
        }
    }
}
