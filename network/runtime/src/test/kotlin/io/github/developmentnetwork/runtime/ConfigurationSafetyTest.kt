package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.config.OfflinePreflight
import io.github.developmentnetwork.runtime.config.OpsWriter
import io.github.developmentnetwork.runtime.config.PaperConfig
import io.github.developmentnetwork.runtime.config.PaperConfigWriter
import io.github.developmentnetwork.runtime.config.VelocityConfig
import io.github.developmentnetwork.runtime.config.VelocityConfigWriter
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationSafetyTest {
    @Test
    fun velocityOutputIsDeterministicAndLobbyIsFirstFailover() {
        val base = Files.createTempDirectory("velocity-test")
        val layout = RuntimeLayout(base)
        AtomicFiles.write(layout.registryFile, "zeta\nalpha\nzeta\n")
        AtomicFiles.write(layout.backend("alpha").port, "31001\n")
        AtomicFiles.write(layout.backend("zeta").port, "31002\n")

        val writer = VelocityConfigWriter()
        val first = writer.write(layout, VelocityConfig(proxyPort = 25565, targetServer = "devhost", onlineMode = false, lobbyPort = 30123))
        val expected = Files.readString(first)
        writer.write(layout, VelocityConfig(proxyPort = 25565, targetServer = "devhost", onlineMode = false, lobbyPort = 30123))

        assertEquals(expected, Files.readString(layout.velocityConfig))
        assertContains(expected, "config-version = \"2.8\"")
        assertContains(expected, "bind = \"0.0.0.0:25565\"")
        assertContains(expected, "online-mode = false")
        assertContains(expected, "lobby = \"devhost:30123\"")
        assertContains(expected, "alpha = \"devhost:31001\"")
        assertContains(expected, "zeta = \"devhost:31002\"")
        assertContains(expected, "try = [\"lobby\", \"alpha\", \"zeta\"]")
        assertEquals("dev-local-forwarding-secret-change-me\n", Files.readString(layout.forwardingSecret))
    }

    @Test
    fun proxyOnlineModeMayBeEnabledIndependentlyFromPaperOfflineForwarding() {
        val work = Files.createTempDirectory("preflight-proxy-online")
        val proxy = work.resolve("velocity.toml")
        Files.writeString(
            proxy,
            """
            online-mode = true
            player-info-forwarding-mode = "modern"
            forwarding-secret-file = "forwarding.secret"
            """.trimIndent() + "\n",
        )
        Files.writeString(work.resolve("forwarding.secret"), "dev-local-forwarding-secret-change-me\n")

        val result = OfflinePreflight().verifyProxy(proxy)

        assertTrue(result.success, result.message)
    }

    @Test
    fun paperPreflightStillRejectsOnlineMode() {
        val work = Files.createTempDirectory("preflight-paper-online")
        PaperConfigWriter().writeManaged(work, PaperConfig(port = 30124))
        Files.writeString(work.resolve("server.properties"), "server-port=30124\nonline-mode=true\n")

        val result = OfflinePreflight().verifyPaper(work)

        assertFalse(result.success)
        assertContains(result.message, "online-mode=false")
    }

    @Test
    fun paperWriterUsesOfflineModernForwardingAndDisablesBungee() {
        val work = Files.createTempDirectory("paper-config")
        PaperConfigWriter().writeManaged(work, PaperConfig(port = 30066))
        assertContains(Files.readString(work.resolve("server.properties")), "online-mode=false")
        assertContains(Files.readString(work.resolve("server.properties")), "server-port=30066")
        assertContains(Files.readString(work.resolve("config/paper-global.yml")), "online-mode: false")
        assertContains(Files.readString(work.resolve("config/paper-global.yml")), "secret: \"dev-local-forwarding-secret-change-me\"")
        assertContains(Files.readString(work.resolve("spigot.yml")), "bungeecord: false")
        assertEquals("eula=true\n", Files.readString(work.resolve("eula.txt")))
    }

    @Test
    fun preflightRejectsOnlineAndUnknownExternalConfigurationWithoutWriting() {
        val work = Files.createTempDirectory("preflight")
        val properties = work.resolve("server.properties")
        Files.writeString(properties, "server-port=30070\nonline-mode=true\n")
        val before = Files.readString(properties)
        val preflight = OfflinePreflight()

        val external = preflight.verifyPaper(work, external = true)
        assertFalse(external.success)
        assertContains(external.message, "online-mode=false")
        assertEquals(before, Files.readString(properties))

        val missing = preflight.verifyProxy(work.resolve("missing-velocity.toml"), owned = false)
        assertFalse(missing.success)
        assertContains(missing.message, "unknown")
    }

    @Test
    fun preflightRegeneratesMissingOwnedProxyAndChecksModernForwardingSecret() {
        val work = Files.createTempDirectory("preflight-owned")
        val proxy = work.resolve("velocity.toml")
        val secret = work.resolve("forwarding.secret")
        var regenerated = false
        val result = OfflinePreflight().verifyProxy(
            proxy,
            owned = true,
            regenerate = {
                regenerated = true
                Files.writeString(
                    proxy,
                    """
                    online-mode = false
                    player-info-forwarding-mode = "modern"
                    forwarding-secret-file = "forwarding.secret"
                    """.trimIndent() + "\n",
                )
                Files.writeString(secret, "expected-secret\n")
            },
            forwardingSecret = "expected-secret",
        )
        assertTrue(regenerated)
        assertTrue(result.success, result.message)
    }

    @Test
    fun preflightScopesPaperForwardingAndRejectsDuplicateEffectiveProperties() {
        val work = Files.createTempDirectory("preflight-structure")
        Files.writeString(work.resolve("server.properties"), "online-mode=false\n")
        Files.createDirectories(work.resolve("config"))
        Files.writeString(
            work.resolve("config/paper-global.yml"),
            """
            unrelated:
              enabled: true
              online-mode: false
              secret: "dev-local-forwarding-secret-change-me"
            proxies:
              velocity:
                enabled: true
                online-mode: false
                secret: "dev-local-forwarding-secret-change-me"
            """.trimIndent() + "\n",
        )
        Files.writeString(work.resolve("spigot.yml"), "settings:\n  bungeecord: false\n")
        val valid = OfflinePreflight().verifyPaper(work)
        assertTrue(valid.success, valid.message)

        Files.writeString(
            work.resolve("config/paper-global.yml"),
            """
            proxies:
              velocity:
                enabled: true
                enabled: false
                online-mode: false
                secret: "dev-local-forwarding-secret-change-me"
            """.trimIndent() + "\n",
        )
        val duplicateYaml = OfflinePreflight().verifyPaper(work)
        assertFalse(duplicateYaml.success)
        assertContains(duplicateYaml.message, "duplicate")

        Files.writeString(work.resolve("server.properties"), "online-mode=false\nonline-mode=true\n")
        val duplicateProperties = OfflinePreflight().verifyPaper(work)
        assertFalse(duplicateProperties.success)
        assertContains(duplicateProperties.message, "duplicate")
    }

    @Test
    fun preflightRejectsProxyWithoutModernForwardingOrExpectedSecret() {
        val work = Files.createTempDirectory("preflight-proxy")
        val proxy = work.resolve("velocity.toml")
        Files.writeString(
            proxy,
            """
            online-mode = false
            player-info-forwarding-mode = "legacy"
            forwarding-secret-file = "forwarding.secret"
            """.trimIndent() + "\n",
        )
        Files.writeString(work.resolve("forwarding.secret"), "wrong\n")
        val result = OfflinePreflight().verifyProxy(proxy, forwardingSecret = "expected")
        assertFalse(result.success)
        assertContains(result.message, "modern")
    }

    @Test
    fun configurationWritersRejectUnsynchronizedCustomForwardingSecrets() {
        val work = Files.createTempDirectory("secret-sync")
        assertFailsWith<IllegalArgumentException> {
            PaperConfigWriter().writeManaged(work.resolve("paper"), PaperConfig(30066, forwardingSecret = "paper-only"))
        }
        assertFailsWith<IllegalArgumentException> {
            VelocityConfigWriter().write(RuntimeLayout(work.resolve("velocity")), VelocityConfig(forwardingSecret = "velocity-only"))
        }
    }

    @Test
    fun offlineOpsUseJavaCompatibleUuidAndLevelFour() {
        val work = Files.createTempDirectory("ops")
        val path = OpsWriter().write(work, listOf("dev"))
        assertEquals(
            """[
  {
    "uuid": "8c6c43b3-2bef-3c48-a644-fe1d4c106c17",
    "name": "dev",
    "level": 4,
    "bypassesPlayerLimit": false
  }
]""",
            Files.readString(path),
        )
    }
}
