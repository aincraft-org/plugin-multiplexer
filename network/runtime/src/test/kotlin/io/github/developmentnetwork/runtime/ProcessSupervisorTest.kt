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
    fun unavailableIdentityMetadataFailsClosedAndLaunchFailureCleansChild() {
        val work = Files.createTempDirectory("process-supervisor-metadata")
        val marker = work.resolve("marker")
        val owned = ProcessSupervisor().launch(fixtureCommand(marker), work)
        try {
            awaitMarker(marker)
            val missing = work.resolve("missing")
            val identity = owned.identity
            assertFalse(ProcessSupervisor().identityReader.matches(identity.copy(executable = missing)))
            assertFalse(ProcessSupervisor().identityReader.matches(identity.copy(workingDirectory = missing)))
        } finally {
            owned.process.destroyForcibly()
            runCatching { owned.process.waitFor() }
        }

        val failingReader = FailingCaptureReader()
        assertFailsWith<IllegalStateException> {
            ProcessSupervisor(failingReader).launch(fixtureCommand(work.resolve("failed")), work)
        }
        assertNotNull(failingReader.captured).let { process ->
            assertFalse(process.isAlive)
        }
    }

    @Test
    fun disappearingWorkingDirectoryFailsClosedDuringMatching() {
        val work = Files.createTempDirectory("process-supervisor-disappearing")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor()
        val owned = supervisor.launch(fixtureCommand(marker), work)
        val moved = work.resolveSibling("${work.fileName}-moved")
        try {
            awaitMarker(marker)
            Files.move(work, moved)
            assertFalse(supervisor.identityReader.matches(owned.identity))
        } finally {
            owned.process.destroyForcibly()
            runCatching { owned.process.waitFor() }
            Files.deleteIfExists(moved.resolve("marker"))
            Files.deleteIfExists(moved)
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
    fun rootIdentityIsRevalidatedBeforeDescendantSignal() {
        val work = Files.createTempDirectory("process-supervisor-root-race")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor(RootLeaseRaceIdentityReader())
        val owned = supervisor.launch(fixtureCommand(marker, "spawn"), work)
        var child: ProcessHandle? = null
        try {
            awaitMarker(marker)
            awaitMarker(marker.resolveSibling("marker.child"))
            child = ProcessHandle.of(
                Files.readString(marker.resolveSibling("marker.child.pid")).trim().toLong(),
            ).orElseThrow()
            assertTrue(child.isAlive)
            assertEquals(
                ProcessSupervisor.TerminationResult.NOT_OWNED,
                supervisor.terminate(owned, Duration.ofSeconds(1)),
            )
            // The root identity was invalidated between discovery and signaling.
            // Neither the owned root nor its still-live descendant may be touched.
            assertTrue(owned.process.isAlive)
            assertTrue(child.isAlive)
        } finally {
            owned.process.destroyForcibly()
            runCatching { owned.process.waitFor() }
            child?.destroyForcibly()
            child?.let { runCatching { it.onExit().get() } }
        }
    }

    @Test
    fun gracefulTerminationStopsOwnedProcess() {
        val work = Files.createTempDirectory("process-supervisor-graceful")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor(PermissiveIdentityReader())
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
        val supervisor = ProcessSupervisor(PermissiveIdentityReader())
        val owned = supervisor.launch(fixtureCommand(marker, "spawn"), work)
        var child: ProcessHandle? = null
        try {
            awaitMarker(marker)
            awaitMarker(childMarker)
            child = ProcessHandle.of(Files.readString(childPidMarker).trim().toLong()).orElseThrow()
            assertTrue(child.isAlive)
            val result = supervisor.terminate(owned, Duration.ofSeconds(2))
            assertEquals(ProcessSupervisor.TerminationResult.GRACEFUL, result)
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
        val supervisor = ProcessSupervisor(PermissiveIdentityReader())
        val owned = supervisor.launch(fixtureCommand(marker, "block"), work)
        try {
            awaitMarkerContent(marker, "ready")
            val result = supervisor.terminate(owned, Duration.ZERO)
            assertEquals(ProcessSupervisor.TerminationResult.FORCED, result)
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
    fun forceEscalationDoesNotClaimTerminationWithLiveTrackedDescendant() {
        val work = Files.createTempDirectory("process-supervisor-live-child")
        val marker = work.resolve("marker")
        val reader = RootOnlyIdentityReader()
        val supervisor = ProcessSupervisor(reader)
        val owned = supervisor.launch(fixtureCommand(marker, "spawn-block"), work)
        var child: ProcessHandle? = null
        try {
            awaitMarker(marker.resolveSibling("marker.child"))
            awaitMarkerContent(marker, "ready")
            val childPid = Files.readString(marker.resolveSibling("marker.child.pid")).trim().toLong()
            assertTrue(childPid > 0)
            child = ProcessHandle.of(childPid).orElseThrow()
            assertEquals(childPid, child.pid())
            assertTrue(child.isAlive)
            assertEquals(
                ProcessSupervisor.TerminationResult.NOT_TERMINATED,
                supervisor.terminate(owned, Duration.ofMillis(150)),
            )
            assertFalse(owned.process.isAlive)
            assertTrue(child.isAlive)
        } finally {
            child?.destroyForcibly()
            child?.let { runCatching { it.onExit().get() } }
            owned.process.destroyForcibly()
            runCatching { owned.process.waitFor() }
        }
    }

    @Test
    fun failedDescendantCaptureRemainsIndeterminate() {
        val work = Files.createTempDirectory("process-supervisor-unknown-child")
        val marker = work.resolve("marker")
        val supervisor = ProcessSupervisor(FailingDescendantReader())
        val owned = supervisor.launch(fixtureCommand(marker, "spawn-block"), work)
        var child: ProcessHandle? = null
        try {
            awaitMarkerContent(marker, "ready")
            awaitMarker(marker.resolveSibling("marker.child"))
            val childPid = Files.readString(marker.resolveSibling("marker.child.pid")).trim().toLong()
            child = ProcessHandle.of(childPid).orElseThrow()
            assertTrue(child.isAlive)
            assertEquals(
                ProcessSupervisor.TerminationResult.NOT_TERMINATED,
                supervisor.terminate(owned, Duration.ofMillis(150)),
            )
            assertFalse(owned.process.isAlive)
            assertTrue(child.isAlive)
        } finally {
            child?.destroyForcibly()
            child?.let { runCatching { it.onExit().get() } }
            owned.process.destroyForcibly()
            runCatching { owned.process.waitFor() }
        }
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
    fun identityGateRefusesRootBeforeAnyTermination() {
        val work = Files.createTempDirectory("process-supervisor-not-terminated")
        val marker = work.resolve("marker")
        val reader = ForceRefusingIdentityReader()
        val supervisor = ProcessSupervisor(reader)
        val owned = supervisor.launch(fixtureCommand(marker), work)
        try {
            awaitMarker(marker)
            assertEquals(
                ProcessSupervisor.TerminationResult.NOT_OWNED,
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
private class PermissiveIdentityReader : io.github.developmentnetwork.runtime.process.ProcessIdentityReader() {
    override fun matches(identity: ProcessIdentity): Boolean = true

    override fun matches(handle: ProcessHandle, identity: ProcessIdentity): Boolean = true
}


private class FailingCaptureReader : io.github.developmentnetwork.runtime.process.ProcessIdentityReader() {
    var captured: Process? = null

    override fun capture(process: Process, cwd: Path): ProcessIdentity {
        captured = process
        throw IllegalStateException("synthetic metadata failure")
    }
}

private class RootLeaseRaceIdentityReader : io.github.developmentnetwork.runtime.process.ProcessIdentityReader() {
    private var rootPid: Long? = null
    private var rootChecks = 0

    override fun matches(identity: ProcessIdentity): Boolean {
        rootPid = identity.pid
        return true
    }

    override fun matches(handle: ProcessHandle, identity: ProcessIdentity): Boolean {
        if (identity.pid == rootPid) {
            rootChecks++
            return rootChecks <= 3
        }
        return true
    }
}

private class RootOnlyIdentityReader : io.github.developmentnetwork.runtime.process.ProcessIdentityReader() {
    private var rootPid: Long? = null

    override fun capture(process: Process, cwd: Path): ProcessIdentity =
        super.capture(process, cwd).also { rootPid = it.pid }

    override fun matches(identity: ProcessIdentity): Boolean = true

    override fun matches(handle: ProcessHandle, identity: ProcessIdentity): Boolean =
        identity.pid == rootPid
}

private class FailingDescendantReader : io.github.developmentnetwork.runtime.process.ProcessIdentityReader() {
    override fun matches(identity: ProcessIdentity): Boolean = true

    override fun matches(handle: ProcessHandle, identity: ProcessIdentity): Boolean = true

    override fun captureDescendant(handle: ProcessHandle, fallbackCwd: Path): ProcessIdentity {
        throw IllegalStateException("synthetic descendant metadata failure")
    }
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
        if (mode == "spawn" || mode == "spawn-block") {
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
        if (mode == "block" || mode == "spawn-block") {
            @Suppress("UNCHECKED_CAST")
            sun.misc.Signal.handle(sun.misc.Signal("TERM"), sun.misc.SignalHandler {
                Files.writeString(marker, "started\nready\nterm\n")
                while (true) Thread.sleep(100)
            })
            Files.writeString(marker, "started\nready\n")
        }
        while (true) Thread.sleep(100)
    }
}
