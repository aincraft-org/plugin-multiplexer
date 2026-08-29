package io.github.developmentnetwork.runtime.controller

import io.github.developmentnetwork.runtime.artifact.ArtifactFetcher
import io.github.developmentnetwork.runtime.artifact.LobbyMapInstaller
import io.github.developmentnetwork.runtime.artifact.LobbyMapOptions
import io.github.developmentnetwork.runtime.config.OfflinePreflight
import io.github.developmentnetwork.runtime.config.OpsWriter
import io.github.developmentnetwork.runtime.config.PaperConfig
import io.github.developmentnetwork.runtime.config.PaperConfigWriter
import io.github.developmentnetwork.runtime.config.VelocityConfig
import io.github.developmentnetwork.runtime.config.VelocityConfigWriter
import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.model.BackendRegistration
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.model.ProcessIdentity
import io.github.developmentnetwork.runtime.process.OwnedProcess
import io.github.developmentnetwork.runtime.process.ProcessSupervisor
import io.github.developmentnetwork.runtime.process.ReadinessProbe
import io.github.developmentnetwork.runtime.registry.PortAllocator
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Infrastructure roles supported by the runtime dispatcher. */
enum class InfrastructureMode { PROXY, FULL }

/** Injectable artifact seam used by lifecycle fixtures; production uses pinned downloads. */
interface RuntimeArtifactProvider {
    fun velocity(destination: Path): Path
    fun paper(destination: Path): Path
}

/** Immutable pinned Velocity/Paper artifact provider. */
class PinnedRuntimeArtifactProvider(private val fetcher: ArtifactFetcher = ArtifactFetcher()) : RuntimeArtifactProvider {
    override fun velocity(destination: Path): Path = fetcher.fetch(
        URI.create("https://fill-data.papermc.io/v1/objects/$VELOCITY_SHA256/velocity-$VELOCITY_VERSION-$VELOCITY_BUILD.jar"),
        VELOCITY_SHA256,
        destination,
    )
    override fun paper(destination: Path): Path = fetcher.fetch(
        URI.create("https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$PAPER_VERSION-$PAPER_BUILD.jar"),
        PAPER_SHA256,
        destination,
    )
    companion object {
        const val VELOCITY_VERSION = "4.1.1"
        const val VELOCITY_BUILD = "24"
        const val VELOCITY_SHA256 = "846411d2d0560fed0f23496ffb89681be528d2c0650ecdcf21724d2d7bd9c1ee"
        const val PAPER_VERSION = "26.2"
        const val PAPER_BUILD = "119"
        const val PAPER_SHA256 = "a8c9140c3075bd7c04973e9cdc491b21bfe6bad472b674ef932a4ae0fec19629"
    }
}

data class InfrastructureRequest(
    val proxyPort: Int = PortAllocator.PROXY_PORT,
    val lobbyPort: Int = PortAllocator.LOBBY_PORT,
    val targetServer: String = "localhost",
    val onlineMode: Boolean = true,
    val owner: String = "runtime-${ProcessHandle.current().pid()}",
    val backendName: String? = null,
    val backendPort: Int? = null,
    val backendOwner: String = owner,
    val backendWorkDir: Path? = null,
    val proxyCommand: List<String> = emptyList(),
    val lobbyCommand: List<String> = emptyList(),
    val backendCommand: List<String> = emptyList(),
    val proxyReadinessPort: Int = proxyPort,
    val lobbyReadinessPort: Int = lobbyPort,
    val backendReadinessHost: String = targetServer,
    val readinessTimeout: Duration = Duration.ofMinutes(4),
    val shutdownTimeout: Duration = Duration.ofSeconds(30),
    val lobbyRestartDelay: Duration = Duration.ofSeconds(2),
    val artifacts: RuntimeArtifactProvider = PinnedRuntimeArtifactProvider(),
    val mapOptions: LobbyMapOptions = LobbyMapOptions(),
    val devUsers: List<String> = listOf("dev"),
)

/** Owns shared proxy/lobby and, in full mode, exactly one controller-owned backend. */
class InfrastructureController(
    private val layout: RuntimeLayout,
    private val processSupervisor: ProcessSupervisor = ProcessSupervisor(),
    private val readiness: EndpointReadiness = EndpointReadiness { host, port, timeout -> ReadinessProbe().await(host, port, timeout) },
    private val preflight: OfflinePreflight = OfflinePreflight(),
    private val velocityWriter: VelocityConfigWriter = VelocityConfigWriter(),
    private val paperWriter: PaperConfigWriter = PaperConfigWriter(),
    private val opsWriter: OpsWriter = OpsWriter(),
    private val mapInstallerFactory: (ArtifactFetcher) -> LobbyMapInstaller = ::LobbyMapInstaller,
) {
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var activeRequest: InfrastructureRequest? = null
    @Volatile private var proxyProcess: OwnedProcess? = null
    @Volatile private var lobbyProcess: OwnedProcess? = null
    @Volatile private var backendProcess: OwnedProcess? = null

    fun run(mode: InfrastructureMode, request: InfrastructureRequest = InfrastructureRequest()): Int {
        activeRequest = request
        stopRequested.set(false)
        return try {
            withProxyLock { runLocked(mode, request) }
        } catch (_: ProxyLockUnavailable) {
            System.err.println("infrastructure controller: proxy lock is already held")
            2
        } catch (error: Exception) {
            System.err.println("infrastructure controller: ${error.message ?: error::class.simpleName}")
            cleanup(request)
            1
        } finally {
            activeRequest = null
            proxyProcess = null
            lobbyProcess = null
            backendProcess = null
            stopRequested.set(false)
        }
    }

    fun stop(request: InfrastructureRequest = activeRequest ?: InfrastructureRequest()): Int {
        stopRequested.set(true)
        return 0
    }

    private fun runLocked(mode: InfrastructureMode, request: InfrastructureRequest): Int {
        validate(request)
        Files.createDirectories(layout.runtimeDir)
        Files.createDirectories(layout.binariesDir)
        Files.createDirectories(layout.logsDir)
        val registry = RegistryStore(layout)
        val backendName = request.backendName?.let(::BackendName)
        if (mode == InfrastructureMode.FULL) {
            require(backendName != null) { "full infrastructure mode requires backendName" }
        }
        val ports = registry.readNames().mapNotNull { name -> registry.readRegistration(name)?.let { name.value to it.port } }.toMap().toMutableMap()
        val existingBackend = backendName?.let(registry::readRegistration)
        if (mode == InfrastructureMode.FULL && existingBackend != null) {
            require(existingBackend.owner == request.backendOwner) {
                "Backend $backendName is already owned by ${existingBackend.owner}"
            }
            require(existingBackend.mode == OwnershipMode.MANAGED) {
                "Backend $backendName is external; full mode cannot start it"
            }
            existingBackend.process?.let { processIdentity ->
                require(!processSupervisor.identityReader.matches(processIdentity)) {
                    "Backend $backendName is already running under owner ${existingBackend.owner}"
                }
            }
        }
        backendName?.let {
            val namesForAllocation = (registry.readNames() + it).distinct()
            val persistedPort = existingBackend?.port
            ports.remove(it.value)
            val resolved = PortAllocator().allocate(
                it,
                namesForAllocation,
                persisted = persistedPort,
                explicit = request.backendPort,
                occupied = ports.values.toSet(),
                reserved = setOf(request.proxyPort, request.lobbyPort),
            )
            ports[it.value] = resolved
        }
        velocityWriter.write(layout, VelocityConfig(request.proxyPort, request.targetServer, request.onlineMode, ports))
        check(preflight.verifyProxy(layout.velocityConfig, owned = true).success) {
            preflight.verifyProxy(layout.velocityConfig, owned = true).message
        }

        val paperArtifact = layout.binariesDir.resolve("paper-${PinnedRuntimeArtifactProvider.PAPER_VERSION}-${PinnedRuntimeArtifactProvider.PAPER_BUILD}.jar")
        val velocityArtifact = layout.binariesDir.resolve("velocity-${PinnedRuntimeArtifactProvider.VELOCITY_VERSION}-${PinnedRuntimeArtifactProvider.VELOCITY_BUILD}.jar")
        val paperJar = if (request.backendCommand.isEmpty() || request.lobbyCommand.isEmpty()) request.artifacts.paper(paperArtifact) else paperArtifact
        val velocityJar = if (request.proxyCommand.isEmpty()) request.artifacts.velocity(velocityArtifact) else velocityArtifact
        val lobbyDir = layout.base.resolve("runtime/lobby")
        paperWriter.writeManaged(lobbyDir, PaperConfig(request.lobbyPort))
        check(preflight.verifyPaper(lobbyDir, external = false).success) {
            preflight.verifyPaper(lobbyDir, external = false).message
        }
        if (request.mapOptions.staticUrl != null || request.mapOptions.randomUrl != null) {
            mapInstallerFactory(ArtifactFetcher()).install(lobbyDir, request.mapOptions)
        }
        val backendDir = if (mode == InfrastructureMode.FULL && backendName != null) {
            val dir = request.backendWorkDir ?: layout.base.resolve("runtime/auto/${backendName.value}")
            Files.createDirectories(dir)
            paperWriter.writeManaged(dir, PaperConfig(request.backendPort ?: ports[backendName.value]!!))
            opsWriter.write(dir, request.devUsers)
            check(preflight.verifyPaper(dir, external = false).success) { preflight.verifyPaper(dir, external = false).message }
            dir
        } else null
        val proxyCommand = request.proxyCommand.ifEmpty { defaultJavaCommand(velocityJar, "-config", layout.velocityConfig.toString()) }
        val lobbyCommand = request.lobbyCommand.ifEmpty { defaultJavaCommand(paperJar, "--nogui") }
        writeControllerOwner(request.owner, request.proxyPort)
        val controlToken = ControlServer.generateToken()
        val control = ControlServer().serve(layout.proxyControl, controlToken) { command ->
            when (command) {
                ControlCommand.Reload -> {
                    val current = registry.readNames().mapNotNull { name -> registry.readRegistration(name)?.let { name.value to it.port } }.toMap()
                    velocityWriter.write(layout, VelocityConfig(request.proxyPort, request.targetServer, request.onlineMode, current))
                    ControlResponse.success("proxy configuration regenerated")
                }
                ControlCommand.Shutdown -> {
                    stopRequested.set(true)
                    ControlResponse.success("shutdown requested")
                }
            }
        }
        try {
            proxyProcess = processSupervisor.launch(proxyCommand, layout.base)
            persistProxyProcess(proxyProcess!!.identity)
            readiness.await(request.targetServer, request.proxyReadinessPort, request.readinessTimeout)
            AtomicFiles.write(layout.proxyReady, "ready\n")
            if (mode == InfrastructureMode.FULL && backendName != null && backendDir != null) {
                backendProcess = launchManagedBackend(backendName, request, ports[backendName.value]!!, backendDir)
            }
            lobbyProcess = launchLobby(lobbyCommand, request)
            superviseLobby(lobbyCommand, request)
            if (!stopRequested.get() && proxyProcess?.process?.isAlive == true) processSupervisor.await(proxyProcess!!)
            return 0
        } finally {
            control.close()
            cleanup(request)
        }
    }

    private fun launchLobby(command: List<String>, request: InfrastructureRequest): OwnedProcess {
        val workDir = layout.base.resolve("runtime/lobby")
        val process = processSupervisor.launch(command, workDir)
        persistLobbyProcess(process.identity)
        AtomicFiles.write(layout.runtimeDir.resolve("lobby.pid"), "${process.pid}\n")
        readiness.await(request.targetServer, request.lobbyReadinessPort, request.readinessTimeout)
        AtomicFiles.write(layout.runtimeDir.resolve("lobby.ready"), "ready\n")
        return process
    }

    private fun superviseLobby(command: List<String>, request: InfrastructureRequest) {
        while (!stopRequested.get()) {
            val process = lobbyProcess ?: return
            if (process.process.isAlive) {
                try { Thread.sleep(50) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    stopRequested.set(true)
                }
                continue
            }
            if (stopRequested.get()) break
            Files.deleteIfExists(layout.runtimeDir.resolve("lobby.pid"))
            Files.deleteIfExists(layout.runtimeDir.resolve("lobby.ready"))
            try { Thread.sleep(request.lobbyRestartDelay.toMillis()) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                stopRequested.set(true)
                break
            }
            if (!stopRequested.get()) lobbyProcess = launchLobby(command, request)
        }
    }

    private fun launchManagedBackend(name: BackendName, request: InfrastructureRequest, port: Int, workDir: Path): OwnedProcess {
        val command = request.backendCommand.ifEmpty {
            defaultJavaCommand(layout.binariesDir.resolve("paper-${PinnedRuntimeArtifactProvider.PAPER_VERSION}-${PinnedRuntimeArtifactProvider.PAPER_BUILD}.jar"), "--nogui")
        }
        val process = processSupervisor.launch(command, workDir)
        val registration = BackendRegistration(name, port, request.backendOwner, OwnershipMode.MANAGED, process.identity)
        RegistryStore(layout).register(registration)
        readiness.await(request.backendReadinessHost, port, request.readinessTimeout)
        AtomicFiles.write(layout.backend(name).ready, "ready\n")
        return process
    }

    private fun cleanup(request: InfrastructureRequest) {
        stopRequested.set(true)
        backendProcess?.let { runCatching { processSupervisor.terminate(it, request.shutdownTimeout) } }
        lobbyProcess?.let { runCatching { processSupervisor.terminate(it, request.shutdownTimeout) } }
        proxyProcess?.let { runCatching { processSupervisor.terminate(it, request.shutdownTimeout) } }
        request.backendName?.let { raw ->
            runCatching {
                val name = BackendName(raw)
                if (RegistryStore(layout).readRegistration(name)?.owner == request.backendOwner) RegistryStore(layout).unregister(name, request.backendOwner)
            }
        }
        listOf(layout.proxyReady, layout.proxyPid, layout.proxyOwner, layout.runtimeDir.resolve("lobby.ready"), layout.runtimeDir.resolve("lobby.pid")).forEach { Files.deleteIfExists(it) }
    }

    private fun writeControllerOwner(owner: String, proxyPort: Int) {
        AtomicFiles.write(layout.proxyOwner, "owner=$owner\npid=${ProcessHandle.current().pid()}\nport=$proxyPort\nmode=infrastructure\n")
    }

    private fun persistProxyProcess(identity: ProcessIdentity) {
        val owner = AtomicFiles.readIfExists(layout.proxyOwner).orEmpty()
        AtomicFiles.write(layout.proxyOwner, owner + "child-pid=${identity.pid}\nchild-start=${identity.startInstant}\nchild-executable=${identity.executable}\nchild-working-directory=${identity.workingDirectory}\n")
    }

    private fun persistLobbyProcess(identity: ProcessIdentity) {
        val owner = AtomicFiles.readIfExists(layout.proxyOwner).orEmpty()
        AtomicFiles.write(layout.proxyOwner, owner + "lobby-pid=${identity.pid}\nlobby-start=${identity.startInstant}\nlobby-executable=${identity.executable}\nlobby-working-directory=${identity.workingDirectory}\n")
    }
    private fun defaultJavaCommand(jar: Path, vararg args: String): List<String> = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString(), *args)

    private fun validate(request: InfrastructureRequest) {
        require(request.owner.isNotBlank() && '\n' !in request.owner && '\r' !in request.owner) { "Infrastructure owner must be a single line" }
        require(request.proxyPort in 1024..65535) { "Proxy port must be in 1024..65535: ${request.proxyPort}" }
        require(request.lobbyPort in 1024..65535) { "Lobby port must be in 1024..65535: ${request.lobbyPort}" }
        require(request.proxyPort != request.lobbyPort) { "Proxy and lobby ports must differ" }
        require(request.targetServer.isNotBlank()) { "Target server must not be blank" }
        require(!request.readinessTimeout.isNegative) { "Readiness timeout must not be negative" }
    }

    private inline fun <T> withProxyLock(action: () -> T): T {
        Files.createDirectories(layout.runtimeDir)
        FileChannel.open(layout.proxyLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            lock ?: throw ProxyLockUnavailable()
            lock.use { return action() }
        }
    }

    private class ProxyLockUnavailable : IllegalStateException()
}

fun interface EndpointReadiness {
    fun await(host: String, port: Int, timeout: Duration)
}

data class ManagedBackendRequest(
    val name: String,
    val owner: String,
    val port: Int,
    val workDir: Path,
    val paperCommand: List<String> = emptyList(),
    val artifacts: RuntimeArtifactProvider = PinnedRuntimeArtifactProvider(),
    val readinessHost: String = "localhost",
    val readinessTimeout: Duration = Duration.ofMinutes(4),
    val shutdownTimeout: Duration = Duration.ofSeconds(30),
    val devUsers: List<String> = listOf("dev"),
)

class ManagedBackendController(
    private val layout: RuntimeLayout,
    private val processSupervisor: ProcessSupervisor = ProcessSupervisor(),
    private val readiness: EndpointReadiness = EndpointReadiness { host, port, timeout -> ReadinessProbe().await(host, port, timeout) },
    private val preflight: OfflinePreflight = OfflinePreflight(),
    private val paperWriter: PaperConfigWriter = PaperConfigWriter(),
    private val opsWriter: OpsWriter = OpsWriter(),
) {
    @Volatile private var active: OwnedProcess? = null
    @Volatile private var activeRequest: ManagedBackendRequest? = null
    private val stopRequested = AtomicBoolean(false)

    fun run(request: ManagedBackendRequest): Int {
        activeRequest = request
        stopRequested.set(false)
        val name = BackendName(request.name)
        val state = layout.backend(name)
        val registry = RegistryStore(layout)
        val existing = registry.readRegistration(name)
        if (existing != null) {
            require(existing.owner == request.owner) { "Backend $name is already owned by ${existing.owner}" }
            require(existing.mode == OwnershipMode.MANAGED) { "Backend $name is external; managed controller cannot claim it" }
        }
        val port = existing?.port ?: request.port
        return try {
            Files.createDirectories(request.workDir)
            Files.createDirectories(layout.runtimeDir)
            val paperJar = layout.binariesDir.resolve("paper-${PinnedRuntimeArtifactProvider.PAPER_VERSION}-${PinnedRuntimeArtifactProvider.PAPER_BUILD}.jar")
            if (request.paperCommand.isEmpty()) request.artifacts.paper(paperJar)
            paperWriter.writeManaged(request.workDir, PaperConfig(port))
            opsWriter.write(request.workDir, request.devUsers)
            check(preflight.verifyPaper(request.workDir, external = false).success) { preflight.verifyPaper(request.workDir, external = false).message }
            val command = request.paperCommand.ifEmpty { listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", paperJar.toString(), "--nogui") }
            val process = processSupervisor.launch(command, request.workDir)
            active = process
            registry.register(BackendRegistration(name, port, request.owner, OwnershipMode.MANAGED, process.identity))
            readiness.await(request.readinessHost, port, request.readinessTimeout)
            AtomicFiles.write(state.ready, "ready\n")
            while (process.process.isAlive && !stopRequested.get()) Thread.sleep(50)
            0
        } catch (error: Exception) {
            System.err.println("managed backend ${request.name}: ${error.message ?: error::class.simpleName}")
            1
        } finally {
            cleanup(request)
            active = null
            activeRequest = null
        }
    }

    fun stop(request: ManagedBackendRequest = activeRequest ?: error("No managed backend is running")): Int {
        stopRequested.set(true)
        active?.let { runCatching { processSupervisor.terminate(it, request.shutdownTimeout) } }
        return 0
    }

    private fun cleanup(request: ManagedBackendRequest) {
        val name = runCatching { BackendName(request.name) }.getOrNull() ?: return
        active?.let { runCatching { processSupervisor.terminate(it, request.shutdownTimeout) } }
        val existing = runCatching { RegistryStore(layout).readRegistration(name) }.getOrNull()
        if (existing?.mode == OwnershipMode.MANAGED && existing.owner == request.owner) runCatching { RegistryStore(layout).unregister(name, request.owner) }
    }
}
// Compatibility aliases keep request names available beside controllers.
typealias ReloadNetworkRequest = io.github.developmentnetwork.runtime.service.ReloadNetworkRequest
typealias StopNetworkRequest = io.github.developmentnetwork.runtime.service.StopNetworkRequest
typealias RestartBackendRequest = io.github.developmentnetwork.runtime.service.RestartBackendRequest
