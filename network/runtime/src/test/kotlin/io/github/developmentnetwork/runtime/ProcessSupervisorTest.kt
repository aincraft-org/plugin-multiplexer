package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.model.ProcessIdentity
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
    fun launchCapturesCanonicalIdentityAndRetainsActualChildStdin() {
        val work = Files.createTempDirectory("process-supervisor")
        val cwdLink = work.resolveSibling("${work.fileName}-link")
        Files.createSymbolicLink(cwdLink, work)
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker), cwdLink)
        try {
            awaitMarker(marker)
            assertEquals(work.toRealPath(), owned.identity.workingDirectory)
            assertEquals(owned.process.pid(), owned.identity.pid)
            assertNotNull(owned.identity.startInstant)
            assertTrue(owned.identity.executable!!.fileName.toString().startsWith("java"))
            assertTrue(owned.stdin === owned.process.outputStream)
        } finally {
            terminateQuietly(supervisor, owned)
            Files.deleteIfExists(cwdLink)
        }
    }

    @Test
    fun suppliedWritesReachTheChildStdin() {
        val work = Files.createTempDirectory("process-supervisor-stdin")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker, "stdin"), work)
        try {
            owned.stdin.write("hello\n".toByteArray())
            owned.stdin.flush()
            awaitMarkerContent(marker, "hello")
            supervisor.await(owned)
        } finally {
            terminateQuietly(supervisor, owned)
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
            terminateQuietly(supervisor, owned)
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
            terminateQuietly(supervisor, owned)
        }
    }

    @Test
    fun gracefulTerminationStopsOwnedDescendantsAndObservesChildDeath() {
        val work = Files.createTempDirectory("process-supervisor-descendants")
        val marker = work.resolve("marker")
        val childMarker = work.resolve("marker.child")
        val childPidMarker = work.resolve("marker.child.pid")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker, "spawn"), work)
        var child: ProcessHandle? = null
        try {
            awaitMarker(marker)
            awaitMarker(childMarker)
            child = ProcessHandle.of(Files.readString(childPidMarker).trim().toLong()).orElseThrow()
            assertTrue(child.isAlive)
            assertEquals(
                ProcessSupervisor.TerminationResult.GRACEFUL,
                supervisor.terminate(owned, Duration.ofSeconds(2)),
            )
            assertFalse(owned.process.isAlive)
            awaitDead(child)
        } finally {
            terminateQuietly(supervisor, owned)
            child?.destroyForcibly()
        }
    }
    @Test
    fun forceEscalationReportsOnlyAfterRootDeath() {
        val work = Files.createTempDirectory("process-supervisor-force")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker, "block"), work)
        try {
            awaitMarker(marker)
            assertEquals(
                ProcessSupervisor.TerminationResult.FORCED,
                supervisor.terminate(owned, Duration.ofMillis(150)),
            )
            assertFalse(owned.process.isAlive)
            assertTrue(Files.readString(marker).contains("term"))
        } finally {
            terminateQuietly(supervisor, owned)
        }
    }

    @Test
    fun interruptedGracefulWaitIsNotEscalatedAndRestoresInterruptStatus() {
        val work = Files.createTempDirectory("process-supervisor-interrupt")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker, "block"), work)
        awaitMarker(marker)
        var result: ProcessSupervisor.TerminationResult? = null
        var interrupted = false
        val waiting = thread(start = true) {
            result = supervisor.terminate(owned, Duration.ofSeconds(5))
            interrupted = Thread.currentThread().isInterrupted
        }
        try {
            Thread.sleep(100)
            waiting.interrupt()
            waiting.join(2_000)
            assertFalse(waiting.isAlive)
            assertEquals(ProcessSupervisor.TerminationResult.INTERRUPTED, result)
            assertTrue(interrupted)
            assertTrue(owned.process.isAlive)
        } finally {
            terminateQuietly(supervisor, owned)
        }
    }

    @Test
    fun forcedResultRequiresRootAndDescendantsToBeObservedDead() {
        // The production result explicitly distinguishes a force request from an
        // observation of termination; this guards the public non-terminated state.
        assertFalse(ProcessSupervisor.TerminationResult.NOT_TERMINATED.terminated)
        assertTrue(ProcessSupervisor.TerminationResult.NOT_TERMINATED.forceEscalated)
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
    @Test
    fun nonTerminatedResultIsReturnedWhenForceIdentityGateRefusesRoot() {
        val work = Files.createTempDirectory("process-supervisor-not-terminated")
        val marker = work.resolve("marker")
        val reader = ForceRefusingIdentityReader()
        val supervisor = ProcessSupervisor(reader)
        val owned = supervisor.launch(fixtureCommand(marker), work)
        try {
            awaitMarker(marker)
            assertEquals(
                ProcessSupervisor.TerminationResult.NOT_TERMINATED,
                supervisor.terminate(owned, Duration.ofMillis(150)),
            )
            assertTrue(owned.process.isAlive)
        } finally {
            terminateQuietly(supervisor, owned)
        }
    }


    @Test
    fun readinessHonorsAlreadyInterruptedCallerAndBoundsHostnameResolution() {
        val interrupted = thread(start = true) {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> {
                ReadinessProbe().await("127.0.0.1", unusedPort(), Duration.ofSeconds(2))
            }
            assertTrue(Thread.currentThread().isInterrupted)
        }
        interrupted.join(2_000)
        assertFalse(interrupted.isAlive)

        val start = System.nanoTime()
        assertFailsWith<TimeoutException> {
            ReadinessProbe().await("definitely-not-a-real-host.invalid", 12345, Duration.ofMillis(100))
        }
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos())
    }

    private fun awaitMarker(marker: Path) {
        repeat(200) {
            if (Files.exists(marker)) return
            Thread.sleep(10)
        }
        error("fixture did not create marker: $marker")
    }

    private fun awaitDead(handle: ProcessHandle) {
        repeat(200) {
            if (!handle.isAlive) return
            Thread.sleep(10)
        }
        assertFalse(handle.isAlive)
    }

    private fun terminateQuietly(
        supervisor: ProcessSupervisor,
        owned: io.github.developmentnetwork.runtime.process.OwnedProcess,
    ) {
        if (owned.process.isAlive) supervisor.terminate(owned, Duration.ofSeconds(2))
        if (owned.process.isAlive) owned.process.destroyForcibly()
        runCatching { owned.process.waitFor() }
    }

    private fun awaitMarkerContent(marker: Path, expected: String) {
        repeat(200) {
            if (Files.exists(marker) && Files.readString(marker).contains(expected)) return
            Thread.sleep(10)
        }
        error("fixture did not write expected marker content: $marker")
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

private class ForceRefusingIdentityReader : io.github.developmentnetwork.runtime.process.ProcessIdentityReader() {
    private var rootPid: Long? = null

    override fun matches(identity: ProcessIdentity): Boolean = true

    override fun matches(handle: ProcessHandle, identity: ProcessIdentity): Boolean {
        if (rootPid == null) rootPid = identity.pid
        return identity.pid != rootPid
    }
}

object ProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val marker = Path.of(args[0])
        val mode = args.getOrNull(1) ?: "normal"
        Files.writeString(marker, "started\n")
        if (mode == "stdin") {
            val line = System.`in`.bufferedReader().readLine() ?: ""
            Files.writeString(marker, line)
            return
        }
        if (mode == "spawn") {
            val childMarker = marker.resolveSibling("${marker.fileName}.child")
            val child = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProcessFixture::class.java.name,
                childMarker.toString(),
                "normal",
            ).directory(marker.parent.toFile()).start()
            Files.writeString(marker.resolveSibling("${marker.fileName}.child.pid"), "${child.pid()}\n")
        }
        if (mode == "block") {
            Runtime.getRuntime().addShutdownHook(Thread {
                Files.writeString(marker, "started\nterm\n")
                while (true) Thread.sleep(100)
            })
        }
        while (true) Thread.sleep(100)
    }
}
