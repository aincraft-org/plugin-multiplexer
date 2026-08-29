package io.github.developmentnetwork

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Test

class ConsumingProjectIntegrationTest {
    @Test
    fun `provider defaults and configured values are visible to a composite consumer`() {
        val consumer = project(
            """
            tasks.register("printNetworkSettings") {
                doLast {
                    val extension = project.extensions.getByName("developmentNetwork")
                    val base = extension.javaClass.getMethod("getNetworkBase").invoke(extension) as org.gradle.api.file.DirectoryProperty
                    val backend = extension.javaClass.getMethod("getNetworkBackend").invoke(extension) as org.gradle.api.provider.Property<*>
                    val proxyPort = extension.javaClass.getMethod("getNetworkProxyPort").invoke(extension) as org.gradle.api.provider.Property<*>
                    val lobbyPort = extension.javaClass.getMethod("getNetworkLobbyPort").invoke(extension) as org.gradle.api.provider.Property<*>
                    println("networkBase=" + project.projectDir.toPath().relativize(base.get().asFile.toPath()))
                    println("networkBackend=" + backend.get())
                    println("networkProxyPort=" + proxyPort.get())
                    println("networkLobbyPort=" + lobbyPort.get())
                }
            }
            """.trimIndent(),
        )
        val defaults = run(consumer, "printNetworkSettings")
        assertContains(defaults.output, "networkBase=run/network")
        assertContains(defaults.output, "networkBackend=consumer")
        assertContains(defaults.output, "networkProxyPort=25565")
        assertContains(defaults.output, "networkLobbyPort=30066")

        val configured = run(
            consumer,
            "printNetworkSettings",
            "-PnetworkBase=custom/network",
            "-PnetworkProxyPort=25580",
            "-PnetworkLobbyPort=30100",
        )
        assertContains(configured.output, "custom/network")
        assertContains(configured.output, "25580")
        assertContains(configured.output, "networkLobbyPort=30100")
    }

    @Test
    fun `registerBackend rejects missing or invalid port before launching runtime`() {
        val consumer = project("""
            tasks.named("registerBackend") { doLast { println("RUNTIME_LAUNCHED") } }
        """.trimIndent())
        val failure = assertFails { run(consumer, "registerBackend") } as UnexpectedBuildFailure
        assertContains(failure.buildResult.output, "registerBackend requires -PnetworkBackendPort")
        assertTrue("RUNTIME_LAUNCHED" !in failure.buildResult.output)

        val invalid = assertFails { run(consumer, "registerBackend", "-PnetworkBackendPort=80") } as UnexpectedBuildFailure
        assertContains(invalid.buildResult.output, "1024..65535")
    }

    @Test
    fun `managed tasks honor the configured jar task dependency`() {
        val consumer = project(
            """
            tasks.register("pluginJar") { doLast { println("CUSTOM_JAR_RAN") } }
            """.trimIndent(),
        )
        val result = run(consumer, "runBackend", "--dry-run", "-PnetworkJarTask=pluginJar")
        assertContains(result.output, ":pluginJar SKIPPED")
        assertContains(result.output, ":runBackend SKIPPED")
    }

    @Test
    fun `composite consumer executes a runtime-backed short task`() {
        val consumer = project("")
        val proxy = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val lobby = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        try {
            val failure = assertFails {
                run(
                    consumer,
                    "networkStatus",
                    "-PnetworkTargetServer=127.0.0.1",
                    "-PnetworkProxyPort=${proxy.localPort}",
                    "-PnetworkLobbyPort=${lobby.localPort}",
                    "-PnetworkTimeout=1",
                )
            } as UnexpectedBuildFailure
            assertContains(failure.buildResult.output, "network runtime exited with code 1")
            assertTrue("ClassNotFoundException" !in failure.buildResult.output)
        } finally {
            proxy.close()
            lobby.close()
        }
    }

    private fun project(buildScript: String) = Files.createTempDirectory("network-consuming-project").also { dir ->
        dir.resolve("settings.gradle.kts").writeText(
            "pluginManagement { includeBuild(${networkBuild().absolutePath.quote()}) }\n" +
                "rootProject.name = \"consumer\"\n",
        )
        dir.resolve("build.gradle.kts").writeText(
            "plugins { id(\"io.github.development-network\") }\n$buildScript\n",
        )
    }

    private fun run(project: java.nio.file.Path, vararg arguments: String) = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withArguments(*arguments, "--console=plain")
        .withPluginClasspath()
        .forwardOutput()
        .build()

    private fun networkBuild() = java.io.File("build.gradle.kts").absoluteFile.parentFile

    private fun String.quote() = replace("\\", "\\\\").replace("\"", "\\\"").let { "\"$it\"" }
}
