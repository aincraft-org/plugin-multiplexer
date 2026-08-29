package io.github.developmentnetwork

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test

class DevNetworkPluginSurfaceTest {
    @Test
    fun `embedded runtime extracts into a clean Gradle user home`() {
        val network = networkBuild()
        GradleRunner.create()
            .withProjectDir(network)
            .withArguments("jar", "--console=plain")
            .forwardOutput()
            .build()
        val pluginJar = network.resolve("build/libs").listFiles { file ->
            file.extension == "jar" && !file.name.contains("-plain")
        }!!.single()
        val userHome = Files.createTempDirectory("clean-gradle-user-home").toFile()
        java.net.URLClassLoader(arrayOf(pluginJar.toURI().toURL()), null).use { loader ->
            val extracted = RuntimeArtifactLauncher.extract(userHome, loader)
            assertTrue(extracted.toPath().startsWith(userHome.toPath()))
            java.util.jar.JarFile(extracted).use { jar ->
                assertTrue(jar.getEntry("io/github/developmentnetwork/runtime/RuntimeMainKt.class") != null)
            }
        }
    }

    @Test
    fun `consumer exposes all nine network tasks with stable descriptions`() {
        val consumer = consumerProject()
        val result = GradleRunner.create()
            .withProjectDir(consumer.toFile())
            .withArguments("tasks", "--all", "--console=plain")
            .withPluginClasspath()
            .forwardOutput()
            .build()

        val expected = mapOf(
            "runProxy" to "Own and run the shared Velocity proxy plus lobby",
            "registerBackend" to "Attach an already-running backend (never starts or stops Paper)",
            "unregisterBackend" to "Remove this project's external backend registration",
            "runBackend" to "Build, register, and run this project's managed Paper backend",
            "runNetwork" to "Run a one-project full network (proxy + lobby + backend)",
        )
        expected.forEach { (task, description) ->
            assertTrue(result.output.contains(task), "missing task $task")
            assertTrue(result.output.contains(description), "missing description for $task")
        }
        listOf("stopNetwork", "reloadNetwork", "restartBackend", "networkStatus").forEach { task ->
            assertTrue(result.output.contains(task), "missing added task $task")
        }
        assertEquals(9, Regex("(?m)^(runProxy|registerBackend|unregisterBackend|runBackend|runNetwork|stopNetwork|reloadNetwork|restartBackend|networkStatus) - ").findAll(result.output).count())
    }

    private fun consumerProject() = Files.createTempDirectory("network-plugin-surface").also { dir ->
        dir.resolve("settings.gradle.kts").writeText(
            "pluginManagement { includeBuild(${networkBuild().absolutePath.quote()}) }\n" +
                "rootProject.name = \"consumer\"\n",
        )
        dir.resolve("build.gradle.kts").writeText(
            "plugins { id(\"io.github.development-network\") }\n",
        )
    }

    private fun networkBuild() = java.io.File("build.gradle.kts").absoluteFile.parentFile

    private fun String.quote() = replace("\\", "\\\\").replace("\"", "\\\"").let { "\"$it\"" }
}
