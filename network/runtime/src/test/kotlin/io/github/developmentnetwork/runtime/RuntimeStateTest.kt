package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.FileLocks
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeStateTest {
    @Test
    fun layoutUsesCanonicalRuntimeStatePaths() {
        val base = Files.createTempDirectory("runtime-layout")
        val layout = RuntimeLayout(base)
        val backend = layout.backend(BackendName("my_backend"))

        assertEquals(base.resolve("runtime"), layout.runtimeDir)
        assertEquals(base.resolve("binaries"), layout.binariesDir)
        assertEquals(base.resolve("logs"), layout.logsDir)
        assertEquals(base.resolve("runtime/velocity.toml"), layout.velocityConfig)
        assertEquals(base.resolve("runtime/forwarding.secret"), layout.forwardingSecret)
        assertEquals(base.resolve("runtime/backends.txt"), layout.registryFile)
        assertEquals(base.resolve("runtime/proxy.lock"), layout.proxyLock)
        assertEquals(base.resolve("runtime/register.lock"), layout.registrationLock)
        assertEquals(base.resolve("runtime/my_backend.port"), backend.port)
        assertEquals(base.resolve("runtime/my_backend.owner"), backend.owner)
        assertEquals(base.resolve("runtime/my_backend.pid"), backend.pid)
        assertEquals(base.resolve("runtime/my_backend.ready"), backend.ready)
        assertEquals(base.resolve("runtime/my_backend.auto-dir"), backend.autoDir)
    }

    @Test
    fun atomicTextWritesReplaceTargetAndDoNotLeaveTemporaryFiles() {
        val directory = Files.createTempDirectory("atomic-write")
        val target = directory.resolve("state.txt")
        AtomicFiles.write(target, "first")
        assertEquals("first", Files.readString(target))

        AtomicFiles.write(target, "second")

        assertEquals("second", Files.readString(target))
        assertEquals(listOf(target), Files.list(directory).use { it.toList() })
    }

    @Test
    fun fileLockExcludesAnotherChannelUntilFirstActionReleases() {
        val base = Files.createTempDirectory("lock-exclusion")
        val locks = FileLocks(RuntimeLayout(base))
        val secondEntered = CountDownLatch(1)
        lateinit var second: Thread

        locks.withProxyLock {
            second = thread(start = true) {
                locks.withProxyLock {
                    secondEntered.countDown()
                }
            }
            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
        }
        assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
        second.join(2_000)
        assertFalse(second.isAlive)
    }
}
