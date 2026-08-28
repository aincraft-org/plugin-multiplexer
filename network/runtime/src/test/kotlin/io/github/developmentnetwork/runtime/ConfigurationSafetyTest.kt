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
        val first = writer.write(layout, VelocityConfig(proxyPort = 25565, targetServer = "devhost", onlineMode = false))
        val expected = Files.readString(first)
        writer.write(layout, VelocityConfig(proxyPort = 25565, targetServer = "devhost", onlineMode = false))

        assertEquals(expected, Files.readString(layout.velocityConfig))
        assertContains(expected, "config-version = \"2.8\"")
        assertContains(expected, "bind = \"0.0.0.0:25565\"")
        assertContains(expected, "online-mode = false")
        assertContains(expected, "alpha = \"devhost:31001\"")
        assertContains(expected, "zeta = \"devhost:31002\"")
        assertContains(expected, "try = [\"lobby\", \"alpha\", \"zeta\"]")
        assertEquals("dev-local-forwarding-secret-change-me\n", Files.readString(layout.forwardingSecret))
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
