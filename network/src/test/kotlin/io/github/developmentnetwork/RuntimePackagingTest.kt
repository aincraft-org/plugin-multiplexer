package io.github.developmentnetwork

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Test
import kotlin.io.path.readBytes
import java.util.jar.JarInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

class RuntimePackagingTest {
    @Test
    fun `extracts a verified runtime jar and reuses matching cache entry`() {
        val gradleUserHome = Files.createTempDirectory("runtime-cache").toFile()
        val runtimeJar = runtimeJarBytes("first")
        val loader = ResourceClassLoader(runtimeJar)

        val extracted = RuntimeArtifactLauncher.extract(gradleUserHome, loader)
        assertTrue(extracted.isFile)
        assertTrue(extracted.toPath().startsWith(gradleUserHome.toPath()))
        JarFile(extracted).use { jar ->
            assertNotNull(jar.getEntry("runtime.txt"))
        }
        val originalTimestamp = extracted.lastModified()

        val reused = RuntimeArtifactLauncher.extract(gradleUserHome, loader)
        assertEquals(extracted, reused)
        assertEquals(originalTimestamp, reused.lastModified())
        assertContentEquals(runtimeJar, reused.readBytes())
    }

    @Test
    fun `replaces a checksum mismatched cache entry`() {
        val gradleUserHome = Files.createTempDirectory("runtime-cache-mismatch").toFile()
        val expected = runtimeJarBytes("expected")
        val loader = ResourceClassLoader(expected)

        val extracted = RuntimeArtifactLauncher.extract(gradleUserHome, loader)
        extracted.writeBytes(runtimeJarBytes("stale"))

        val replaced = RuntimeArtifactLauncher.extract(gradleUserHome, loader)
        assertEquals(extracted, replaced)
        assertContentEquals(expected, replaced.readBytes())
    }

    @Test
    fun `serializes concurrent extraction to one verified file`() {
        val gradleUserHome = Files.createTempDirectory("runtime-cache-concurrent").toFile()
        val expected = runtimeJarBytes("concurrent")
        val loader = ResourceClassLoader(expected)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val results = (1..32).map {
                executor.submit(Callable { RuntimeArtifactLauncher.extract(gradleUserHome, loader) })
            }.map { it.get() }
            assertTrue(results.all { it == results.first() })
            assertContentEquals(expected, results.first().readBytes())
            JarFile(results.first()).use { jar -> assertNotNull(jar.getEntry("runtime.txt")) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `deletes failed temporary extraction output`() {
        val gradleUserHome = Files.createTempDirectory("runtime-cache-failure").toFile()
        val expected = runtimeJarBytes("failure")
        val loader = FailOnSecondStreamClassLoader(expected)

        assertFailsWith<IOException> {
            RuntimeArtifactLauncher.extract(gradleUserHome, loader)
        }

        val temporaryFiles = gradleUserHome.walkTopDown()
            .filter { it.isFile && it.name.contains(".tmp") }
            .toList()
        assertTrue(temporaryFiles.isEmpty(), "failed extraction left temporary files: $temporaryFiles")
    }

    @Test
    fun `published plugin jar embeds the exact runtime artifact entry`() {
        val networkDir = File("build.gradle.kts").absoluteFile.parentFile
        GradleRunner.create()
            .withProjectDir(networkDir)
            .withArguments("jar")
            .forwardOutput()
            .build()

        val pluginJars = networkDir.resolve("build/libs").listFiles { file ->
            file.extension == "jar" && !file.name.contains("-plain")
        }?.toList().orEmpty()
        assertEquals(1, pluginJars.size)
        JarFile(pluginJars.single()).use { jar ->
            val entry = jar.getEntry("META-INF/development-network/runtime.jar")
            assertNotNull(entry)
            assertTrue(entry.size > 0)
            assertFalse(entry.isDirectory)
            val nestedBytes = jar.getInputStream(entry).use { it.readBytes() }
            JarInputStream(nestedBytes.inputStream()).use { nested ->
                assertNotNull(nested.nextJarEntry)
            }
        }
    }

    @Test
    fun `runtime main rejects unknown command`() {
        val networkDir = File("build.gradle.kts").absoluteFile.parentFile
        GradleRunner.create()
            .withProjectDir(networkDir)
            .withArguments(":runtime:jar")
            .forwardOutput()
            .build()
        val runtimeJar = networkDir.resolve("runtime/build/libs/runtime.jar")
        val result = ProcessBuilder(
            javaExecutable(), "-jar", runtimeJar.absolutePath, "not-a-command"
        ).directory(networkDir).redirectErrorStream(true).start()
        val output = result.inputStream.bufferedReader().use { it.readText() }
        assertTrue(result.waitFor() != 0)
        assertTrue(output.contains("Unknown command"), output)
    }

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return File(System.getProperty("java.home"), "bin/$executable").absolutePath
    }

    private fun runtimeJarBytes(content: String): ByteArray {
        return java.io.ByteArrayOutputStream().use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("runtime.txt"))
                jar.write(content.toByteArray())
                jar.closeEntry()
            }
            output.toByteArray()
        }
    }

    private open class ResourceClassLoader(private val bytes: ByteArray) : ClassLoader(null) {
        override fun getResourceAsStream(name: String) =
            if (name == "META-INF/development-network/runtime.jar") {
                ByteArrayInputStream(bytes)
            } else {
                null
            }
    }

    private class FailOnSecondStreamClassLoader(private val payload: ByteArray) : ResourceClassLoader(payload) {
        private var streams = 0

        override fun getResourceAsStream(name: String) = synchronized(this) {
            streams++
            if (streams == 1) {
                super.getResourceAsStream(name)
            } else {
                object : ByteArrayInputStream(payload) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        throw IOException("simulated extraction failure")
                }
            }
        }
    }
}
