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
        val docs = listOf(
            "README.md" to root.resolve("README.md"),
            "SKILL.md" to root.resolve("SKILL.md"),
        )
        val files = buildList {
            docs.forEach { (_, path) -> add(path) }
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

        val dedicatedCommands = listOf(
            "./gradlew runproxy -pnetworkbase=run/dedicated-network -pnetworkproxyport=25565 -pnetworklobbyport=30069",
            "./gradlew registerbackend -pnetworkbase=run/dedicated-network -pnetworklobbyport=30069 -pnetworkbackend=external-paper -pnetworkbackendport=25566 -pnetworkserverdir=run/external-paper -pnetworkregistrationowner=external-paper-owner",
            "./gradlew networkstatus -pnetworkbase=run/dedicated-network -pnetworkproxyport=25565 -pnetworklobbyport=30069",
        )
        docs.forEach { (name, path) ->
            val document = path.readText().replace("\\", "").replace(Regex("\\s+"), " ").lowercase()
            val commandPositions = dedicatedCommands.map { document.indexOf(it) }
            assertTrue(
                commandPositions.all { it >= 0 } && commandPositions.zipWithNext().all { (first, second) -> first < second },
                "$name missing the ordered dedicated shared network sequence",
            )
            listOf(
                "networklobbyport=30069",
                "networkbackendport=25566",
                "networkserverdir=run/external-paper",
                "networkregistrationowner=external-paper-owner",
                "registration uses the active controller's existing proxy configuration",
                "requires `networklobbyport` and `networkserverdir`",
                "never edits or stops paper",
                "does not prove client routing",
                "connect a client",
                "/server external-paper",
                "clean github actions checkout",
                "java 25 and the committed gradle wrapper",
                "./gradlew clean check",
                "./gradlew assemble",
            ).forEach { phrase ->
                assertTrue(document.contains(phrase), "$name missing documentation contract: $phrase")
            }
        }

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
            "30069",
            "25566",
            "networkBase",
            "networkBackend",
            "networkBackendPort",
            "networkProxyPort",
            "networkLobbyPort",
            "networkTimeout",
            "networkShutdownTimeout",
            "networkControlTimeout",
            "networkServerDir",
            "networkJarTask",
            "networkDevUsers",
            "networkOnlineMode",
            "networkRegistrationOwner",
            "networkTargetServer",
            "networkLobbyMapUrl",
            "networkLobbyMapSha256",
            "networkLobbyMapRandomUrl",
            "dedicated shared network / external backend",
            "never edits or stops paper",
            "does not prove client routing",
            "committed gradle wrapper",
            "clean check",
            "assemble",
            "Velocity 4.1.1",
            "Paper 26.2",
        ).forEach { phrase ->
            assertTrue(lower.contains(phrase.lowercase()), "missing retained contract: $phrase")
        }
    }
}
