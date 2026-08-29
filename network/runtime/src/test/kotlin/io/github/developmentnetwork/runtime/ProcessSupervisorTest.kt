package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.process.ProcessSupervisor
import io.github.developmentnetwork.runtime.process.ReadinessProbe
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessSupervisorTest {
    @Test
    fun launchCapturesIdentityWorkingDirectoryAndRetainsStdin() {
        val work = Files.createTempDirectory("process-supervisor")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(
            fixtureCommand(marker),
            work,
        )
        try {
            awaitMarker(marker)
            assertEquals(work.toAbsolutePath().normalize(), owned.identity.workingDirectory)
            assertEquals(owned.process.pid(), owned.identity.pid)
            assertNotNull(owned.identity.startInstant)
            assertTrue(owned.identity.executable!!.fileName.toString().startsWith("java"))
            assertNotNull(owned.stdin)
        } finally {
            supervisor.terminate(owned, Duration.ofSeconds(2))
        }
    }

    @Test
    fun changedOrReusedPidIsNeverSignalled() {
        val work = Files.createTempDirectory("process-supervisor-owner")
        val alternateCwd = Files.createDirectory(work.resolve("alternate"))
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker), work)
        val externalPidFile = work.resolve("external.pid")
        Files.writeString(externalPidFile, "12345\n")
        try {
            awaitMarker(marker)
            val changed = owned.identity.copy(startInstant = owned.identity.startInstant!!.plusSeconds(1))
            assertFalse(supervisor.identityReader.matches(changed))
            assertFalse(supervisor.identityReader.matches(owned.identity.copy(workingDirectory = alternateCwd)))
            assertEquals(ProcessSupervisor.TerminationResult.NOT_OWNED, supervisor.terminate(
                owned.copy(identity = changed),
                Duration.ofMillis(50),
            ))
            assertTrue(owned.process.isAlive)
            assertEquals("12345\n", Files.readString(externalPidFile))
        } finally {
            supervisor.terminate(owned, Duration.ofSeconds(2))
        }
    }

    @Test
    fun gracefulTerminationStopsOwnedProcess() {
        val work = Files.createTempDirectory("process-supervisor-graceful")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker), work)
        try {
            awaitMarker(marker)
            assertEquals(
                ProcessSupervisor.TerminationResult.GRACEFUL,
                supervisor.terminate(owned, Duration.ofSeconds(2)),
            )
            assertFalse(owned.process.isAlive)
        } finally {
            owned.process.destroyForcibly()
        }
    }

    @Test
    fun gracefulTerminationStopsOwnedDescendants() {
        val work = Files.createTempDirectory("process-supervisor-descendants")
        val marker = work.resolve("marker")
        val childMarker = work.resolve("marker.child")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker, "spawn"), work)
        try {
            awaitMarker(marker)
            awaitMarker(childMarker)
            assertEquals(
                ProcessSupervisor.TerminationResult.GRACEFUL,
                supervisor.terminate(owned, Duration.ofSeconds(2)),
            )
            assertFalse(owned.process.isAlive)
        } finally {
            owned.process.destroyForcibly()
        }
    }

    @Test
    fun gracefulTerminationEscalatesToForceAfterDeadline() {
        val work = Files.createTempDirectory("process-supervisor-force")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker, "block"), work)
        awaitMarker(marker)
        try {
            assertEquals(
                ProcessSupervisor.TerminationResult.FORCED,
                supervisor.terminate(owned, Duration.ofMillis(150)),
            )
            assertFalse(owned.process.isAlive)
            assertTrue(Files.readString(marker).contains("term"))
        } finally {
            owned.process.destroyForcibly()
        }
    }

    @Test
    fun awaitRestoresInterruptStatusWhenInterrupted() {
        val work = Files.createTempDirectory("process-supervisor-await")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker), work)
        awaitMarker(marker)
        val interrupted = thread(start = true) {
            supervisor.await(owned)
            Thread.currentThread().isInterrupted
        }
        interrupted.interrupt()
        interrupted.join(2_000)
        assertFalse(interrupted.isAlive)
        assertTrue(interrupted.isInterrupted)
        supervisor.terminate(owned, Duration.ofSeconds(2))
    }

    @Test
    fun readinessWaitsUntilEndpointAndTimesOut() {
        val probe = ReadinessProbe()
        val server = ServerSocket(0)
        try {
            val ready = thread(start = true) {
                Thread.sleep(100)
                server.accept().use { }
            }
            probe.await("127.0.0.1", server.localPort, Duration.ofSeconds(2))
            ready.join(2_000)
            assertFalse(ready.isAlive)
        } finally {
            server.close()
        }
        assertFailsWith<TimeoutException> {
            probe.await("127.0.0.1", unusedPort(), Duration.ofMillis(100))
        }
    }

    private fun awaitMarker(marker: Path) {
        repeat(100) {
            if (Files.exists(marker)) return
            Thread.sleep(10)
        }
        error("fixture did not create marker: $marker")
    }

    private fun fixtureCommand(marker: Path, mode: String = "normal"): List<String> = listOf(
        javaExecutable(),
        "-cp",
        System.getProperty("java.class.path"),
        ProcessFixture::class.java.name,
        marker.toString(),
        mode,
    )

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun unusedPort(): Int = ServerSocket(0).use { it.localPort }
}

object ProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val marker = Path.of(args[0])
        Files.writeString(marker, "started\n")
        if (args.getOrNull(1) == "spawn") {
            val childMarker = marker.resolveSibling("${marker.fileName}.child")
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProcessFixture::class.java.name,
                childMarker.toString(),
                "normal",
            ).directory(marker.parent.toFile()).start()
        }
        if (args.getOrNull(1) == "block") {
            Runtime.getRuntime().addShutdownHook(Thread {
                Files.writeString(marker, "started\nterm\n")
                while (true) Thread.sleep(100)
            })
        }
        while (true) Thread.sleep(100)
}
}
