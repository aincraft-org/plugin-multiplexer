package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.controller.RuntimeArtifactProvider
import io.github.developmentnetwork.runtime.controller.InfrastructureController
import io.github.developmentnetwork.runtime.controller.InfrastructureMode
import io.github.developmentnetwork.runtime.controller.InfrastructureRequest
import io.github.developmentnetwork.runtime.controller.ManagedBackendController
import io.github.developmentnetwork.runtime.controller.ManagedBackendRequest
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.process.ProcessSupervisor
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Integration fixtures exercise controller ownership without downloading Minecraft artifacts. */
class ControllerLifecycleTest {
    @Test
    fun proxyLockExcludesSecondControllerAndProxyOnlyDoesNotStartRegisteredBackend() {
        val base = Files.createTempDirectory("runtime-controller-lock")
        val layout = RuntimeLayout(base)
        val proxyPort = freePort()
        val lobbyPort = freePort()
        val backendPort = freePort()
        val process = ProcessSupervisor()
        val fixture = FakeJavaServer.command(FakeJavaServer.classPath(), proxyPort, "proxy")
        val lobby = FakeJavaServer.command(FakeJavaServer.classPath(), lobbyPort, "lobby")
        val backend = FakeJavaServer.command(FakeJavaServer.classPath(), backendPort, "backend")
        val request = InfrastructureRequest(
            proxyPort = proxyPort,
            lobbyPort = lobbyPort,
            onlineMode = false,
            proxyCommand = fixture,
            lobbyCommand = lobby,
            backendName = "owned",
            backendPort = backendPort,
            backendCommand = backend,
            backendWorkDir = base.resolve("backend"),
            proxyReadinessPort = proxyPort,
            lobbyReadinessPort = lobbyPort,
            artifacts = FakeArtifacts,
            readinessTimeout = Duration.ofSeconds(5),
        )
        val controller = InfrastructureController(layout, processSupervisor = process)
        val running = thread(start = true) { controller.run(InfrastructureMode.PROXY, request) }
        await(layout.runtimeDir.resolve("proxy.ready"))
        assertEquals(2, InfrastructureController(layout).run(InfrastructureMode.PROXY, request))
        assertFalse(Files.exists(layout.backend("owned").pid), "proxy-only mode must not start a registered backend")
        Files.writeString(layout.proxyControlToken, Files.readString(layout.proxyControlToken))
        io.github.developmentnetwork.runtime.controller.ControlClient().request(
            layout.proxyControl,
            Files.readString(layout.proxyControlToken).trim(),
            io.github.developmentnetwork.runtime.controller.ControlCommand.Shutdown,
            Duration.ofSeconds(5),
        )
        running.join(10_000)
        assertFalse(running.isAlive)
    }

    @Test
    fun managedBackendOwnsOnePaperProcessAndCleansOnlyItsRegistration() {
        val base = Files.createTempDirectory("runtime-managed-controller")
        val layout = RuntimeLayout(base)
        val port = freePort()
        val workDir = base.resolve("managed")
        val request = ManagedBackendRequest(
            name = "one",
            owner = "owner-one",
            port = port,
            workDir = workDir,
            paperCommand = FakeJavaServer.command(System.getProperty("java.class.path"), port, "paper"),
            artifacts = FakeArtifacts,
            readinessTimeout = Duration.ofSeconds(5),
        )
        val controller = ManagedBackendController(layout)
        val running = thread(start = true) { controller.run(request) }
        await(layout.backend("one").ready)
        assertEquals(OwnershipMode.MANAGED, layout.backend("one").mode.let { Files.readString(it).trim().let(OwnershipMode::valueOf) })
        Files.writeString(layout.backend("other").owner, "different\n")
        assertTrue(running.isAlive)
        controller.stop(request)
        running.join(10_000)
        assertFalse(running.isAlive)
        assertFalse(Files.exists(layout.backend("one").owner))
        assertTrue(Files.exists(layout.backend("other").owner))
    }

    @Test
    fun fullModeStartsOnlyItsManagedBackendAndUnexpectedLobbyRestartIsDelayed() {
        val base = Files.createTempDirectory("runtime-full-controller")
        val layout = RuntimeLayout(base)
        val proxyPort = freePort()
        val lobbyPort = freePort()
        val backendPort = freePort()
        val request = InfrastructureRequest(
            proxyPort = proxyPort,
            lobbyPort = lobbyPort,
            onlineMode = false,
            proxyCommand = FakeJavaServer.command(FakeJavaServer.classPath(), proxyPort, "proxy"),
            lobbyCommand = FakeJavaServer.command(FakeJavaServer.classPath(), lobbyPort, "lobby", "--exit-after=250", "--exit-once=${base.resolve("lobby.once")}"),
            backendName = "full",
            backendPort = backendPort,
            backendCommand = FakeJavaServer.command(FakeJavaServer.classPath(), backendPort, "backend"),
            proxyReadinessPort = proxyPort,
            lobbyReadinessPort = lobbyPort,
            artifacts = FakeArtifacts,
            readinessTimeout = Duration.ofSeconds(5),
        )
        val startedAt = System.nanoTime()
        val controller = InfrastructureController(layout)
        val running = thread(start = true) { controller.run(InfrastructureMode.FULL, request) }
        await(layout.backend("full").ready)
        Thread.sleep(2_600)
        assertTrue(Files.exists(layout.runtimeDir.resolve("lobby.pid")))
        assertTrue(System.nanoTime() - startedAt >= 2_600_000_000L)
        controller.stop(request)
        running.join(10_000)
        assertFalse(running.isAlive)
        assertFalse(Files.exists(layout.backend("full").pid))
    }

    private fun await(path: Path) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(Files.exists(path), "timed out waiting for $path")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private object FakeArtifacts : RuntimeArtifactProvider {
        override fun velocity(destination: Path): Path = destination.also { Files.createDirectories(it.parent) }
        override fun paper(destination: Path): Path = destination.also { Files.createDirectories(it.parent) }
    }
}
object FakeJavaServer {
    fun classPath(): String =
        System.getProperty("java.class.path") + java.io.File.pathSeparator +
            kotlin.Unit::class.java.protectionDomain.codeSource.location.path

    fun command(classPath: String, port: Int, label: String, vararg options: String): List<String> =
        listOf("java", "-cp", classPath, "io.github.developmentnetwork.runtime.FakeJavaServerMain", port.toString(), label) + options.toList()
}

@Suppress("unused")
object FakeJavaServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.first().toInt()
        val exitAfter = args.drop(2).firstOrNull { it.startsWith("--exit-after=") }?.substringAfter('=')?.toLongOrNull()
        val exitOnce = args.drop(2).firstOrNull { it.startsWith("--exit-once=") }?.substringAfter('=')?.let(Path::of)
        val shouldExit = exitOnce == null || Files.notExists(exitOnce)
        exitOnce?.let { Files.writeString(it, "started") }
        val server = java.net.ServerSocket(port)
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.close() } })
        if (shouldExit && exitAfter != null) Thread { Thread.sleep(exitAfter); server.close() }.start()
        while (!server.isClosed) runCatching { server.accept().close() }
    }
}
