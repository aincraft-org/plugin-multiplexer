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
import java.io.Closeable
import java.net.ServerSocket
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        const val PAPER_SHA256 = "a8c9140c3075bd7c04973e9cdc491b21fbe6bad472b674ef932a4ae0fec19629"
    }
}
data class InfrastructureRequest(
    val proxyPort: Int = PortAllocator.PROXY_PORT,
    val lobbyPort: Int = PortAllocator.LOBBY_PORT,
    /** The upstream host written to Velocity; locally launched children use loopback readiness. */
    val targetServer: String = "localhost",
    val onlineMode: Boolean = false,
    val owner: String = "runtime-${ProcessHandle.current().pid()}",
    val backendName: String? = null,
    val backendPort: Int? = null,
    val backendOwner: String = owner,
    val backendWorkDir: Path? = null,
    val proxyCommand: List<String> = emptyList(),
    val lobbyCommand: List<String> = emptyList(),
    val backendCommand: List<String> = emptyList(),
    val proxyReadinessHost: String = "127.0.0.1",
    val proxyReadinessPort: Int = proxyPort,
    val lobbyReadinessHost: String = "127.0.0.1",
    val lobbyReadinessPort: Int = lobbyPort,
    val backendReadinessHost: String = "127.0.0.1",
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
    private val wakeMonitor = Object()
    @Volatile private var activeRequest: InfrastructureRequest? = null
    @Volatile private var runThread: Thread? = null
    @Volatile private var proxyProcess: OwnedProcess? = null
    @Volatile private var lobbyProcess: OwnedProcess? = null
    @Volatile private var backendProcess: OwnedProcess? = null
    @Volatile private var backendReservation: BackendReservation? = null

    fun run(mode: InfrastructureMode, request: InfrastructureRequest = InfrastructureRequest()): Int {
        activeRequest = request
        runThread = Thread.currentThread()
        stopRequested.set(false)
        return try {
            withProxyLock { runLocked(mode, request) }
        } catch (_: InterruptedException) {
            if (stopRequested.get()) 0 else {
                System.err.println("infrastructure controller: interrupted")
                1
            }
        } catch (_: ProxyLockUnavailable) {
            System.err.println("infrastructure controller: proxy lock is already held")
            1
        } catch (error: Exception) {
            System.err.println("infrastructure controller: ${error.message ?: error::class.simpleName}")
            1
        } finally {
            runThread = null
            activeRequest = null
            proxyProcess = null
            lobbyProcess = null
            backendProcess = null
            backendReservation = null
            stopRequested.set(false)
        }
    }

    /** Request stop without blocking the caller or leaving readiness/supervision asleep. */
    fun stop(request: InfrastructureRequest = activeRequest ?: InfrastructureRequest()): Int {
        requestStop()
        return 0
    }

    private fun requestStop() {
        stopRequested.set(true)
        synchronized(wakeMonitor) { wakeMonitor.notifyAll() }
        runThread?.takeIf { it !== Thread.currentThread() }?.interrupt()
    }

    private fun runLocked(mode: InfrastructureMode, originalRequest: InfrastructureRequest): Int {
        val request = resolvePorts(originalRequest)
        activeRequest = request
        validate(request)
        Files.createDirectories(layout.runtimeDir)
        Files.createDirectories(layout.binariesDir)
        Files.createDirectories(layout.logsDir)
        val registry = RegistryStore(layout)
        val backendName = request.backendName?.let(::BackendName)
        if (mode == InfrastructureMode.FULL) {
            require(backendName != null) { "full infrastructure mode requires backendName" }
        }

        val registrations = registry.readRegistrations()
        val ports = registrations.associate { it.name.value to it.port }.toMutableMap()
        val existingBackend = backendName?.let(registry::readRegistration)
        if (mode == InfrastructureMode.FULL && existingBackend != null) {
            require(existingBackend.owner == request.backendOwner) {
                "Backend $backendName is already owned by ${existingBackend.owner}"
            }
            require(existingBackend.mode == OwnershipMode.MANAGED) {
                "Backend $backendName is external; full mode cannot start it"
            }
            existingBackend.process?.let { identity ->
                require(!identityIsLive(identity)) {
                    "Backend $backendName is already running under owner ${existingBackend.owner}"
                }
            }
        }

        val resolvedBackendPort = backendName?.let { name ->
            val namesForAllocation = (registrations.map { it.name } + name).distinct()
            val persistedPort = existingBackend?.port
            val occupied = registrations.filter { it.name != name }.map { it.port }.toSet()
            PortAllocator().allocate(
                name,
                namesForAllocation,
                persisted = persistedPort,
                explicit = request.backendPort,
                occupied = occupied,
                reserved = setOf(request.proxyPort, request.lobbyPort),
            ).also { ports[name.value] = it }
        }

        var control: Closeable? = null
        return try {
            velocityWriter.write(layout, VelocityConfig(request.proxyPort, request.targetServer, request.onlineMode, ports))
            check(preflight.verifyProxy(layout.velocityConfig, owned = true).success) {
                preflight.verifyProxy(layout.velocityConfig, owned = true).message
            }

            val paperArtifact = layout.binariesDir.resolve(
                "paper-${PinnedRuntimeArtifactProvider.PAPER_VERSION}-${PinnedRuntimeArtifactProvider.PAPER_BUILD}.jar",
            )
            val velocityArtifact = layout.binariesDir.resolve(
                "velocity-${PinnedRuntimeArtifactProvider.VELOCITY_VERSION}-${PinnedRuntimeArtifactProvider.VELOCITY_BUILD}.jar",
            )
            val paperJar = if (request.backendCommand.isEmpty() || request.lobbyCommand.isEmpty()) {
                request.artifacts.paper(paperArtifact)
            } else {
                paperArtifact
            }
            val velocityJar = if (request.proxyCommand.isEmpty()) request.artifacts.velocity(velocityArtifact) else velocityArtifact

            val lobbyDir = layout.base.resolve("runtime/lobby")
            paperWriter.writeManaged(lobbyDir, PaperConfig(request.lobbyPort))
            opsWriter.write(lobbyDir, request.devUsers)
            check(preflight.verifyPaper(lobbyDir, external = false).success) {
                preflight.verifyPaper(lobbyDir, external = false).message
            }
            if (request.mapOptions.staticUrl != null || request.mapOptions.randomUrl != null) {
                mapInstallerFactory(ArtifactFetcher()).install(lobbyDir, request.mapOptions)
            }

            val backendDir = if (mode == InfrastructureMode.FULL && backendName != null && resolvedBackendPort != null) {
                val dir = request.backendWorkDir ?: layout.base.resolve("runtime/auto/${backendName.value}")
                Files.createDirectories(dir)
                paperWriter.writeManaged(dir, PaperConfig(resolvedBackendPort))
                opsWriter.write(dir, request.devUsers)
                check(preflight.verifyPaper(dir, external = false).success) {
                    preflight.verifyPaper(dir, external = false).message
                }
                dir
            } else {
                null
            }

            val proxyCommand = request.proxyCommand.ifEmpty {
                defaultJavaCommand(velocityJar, "-config", layout.velocityConfig.toString())
            }
            val lobbyCommand = request.lobbyCommand.ifEmpty {
                defaultJavaCommand(paperJar, "--nogui")
            }
            writeControllerOwner(request.owner, request.proxyPort)
            val controlToken = ControlServer.generateToken()
            control = ControlServer().serve(layout.proxyControl, controlToken) { command ->
                when (command) {
                    ControlCommand.Reload -> {
                        val current = RegistryStore(layout).readRegistrations().associate { it.name.value to it.port }
                        velocityWriter.write(
                            layout,
                            VelocityConfig(request.proxyPort, request.targetServer, request.onlineMode, current),
                        )
                        val proxy = proxyProcess ?: error("Proxy process is not running")
                        requireLive(proxy, "proxy")
                        proxy.stdin.write("velocity reload\n".toByteArray(StandardCharsets.UTF_8))
                        proxy.stdin.flush()
                        ControlResponse.success("proxy configuration regenerated and reload delivered")
                    }
                    ControlCommand.Shutdown -> {
                        requestStop()
                        ControlResponse.success("shutdown requested")
                    }
                }
            }

            proxyProcess = processSupervisor.launch(proxyCommand, layout.base)
            persistProxyProcess(proxyProcess!!.identity)
            awaitReadiness(
                proxyProcess!!,
                request.proxyReadinessHost,
                request.proxyReadinessPort,
                request.readinessTimeout,
                "proxy",
            )
            AtomicFiles.write(layout.proxyReady, "ready\n")

            if (mode == InfrastructureMode.FULL && backendName != null && backendDir != null && resolvedBackendPort != null) {
                // Reserve the exact name/port before creating the child. A later
                // rollback is allowed to remove only the identity this run registered.
                registry.register(
                    BackendRegistration(
                        backendName,
                        resolvedBackendPort,
                        request.backendOwner,
                        OwnershipMode.MANAGED,
                        null,
                    ),
                )
                backendReservation = BackendReservation(backendName, request.backendOwner, resolvedBackendPort)
                backendProcess = launchManagedBackend(backendName, request, resolvedBackendPort, backendDir)
            }

            lobbyProcess = launchLobby(lobbyCommand, request)
            superviseLobby(lobbyCommand, request)
            0
        } finally {
            // Keep the authenticated control lease through all child cleanup.
            cleanup(request)
            control?.close()
        }
    }

    private fun launchLobby(command: List<String>, request: InfrastructureRequest): OwnedProcess {
        val workDir = layout.base.resolve("runtime/lobby")
        val process = processSupervisor.launch(command, workDir)
        // Retain the handle before metadata, readiness, or other operation can fail.
        lobbyProcess = process
        persistLobbyProcess(process.identity)
        awaitReadiness(
            process,
            request.lobbyReadinessHost,
            request.lobbyReadinessPort,
            request.readinessTimeout,
            "lobby",
        )
        AtomicFiles.write(layout.runtimeDir.resolve("lobby.ready"), "ready\n")
        return process
    }

    private fun superviseLobby(command: List<String>, request: InfrastructureRequest) {
        while (true) {
            if (stopRequested.get()) return
            val proxy = proxyProcess ?: error("Proxy process handle was lost")
            if (!proxy.process.isAlive) {
                if (stopRequested.get()) return
                throw IllegalStateException("proxy process exited unexpectedly")
            }
            if (!processSupervisor.identityReader.matches(proxy.identity)) {
                throw IllegalStateException("proxy process identity no longer matches its owner lease")
            }

            val lobby = lobbyProcess ?: error("Lobby process handle was lost")
            if (lobby.process.isAlive) {
                if (!processSupervisor.identityReader.matches(lobby.identity)) {
                    throw IllegalStateException("lobby process identity no longer matches its owner lease")
                }
                awaitWake(50)
                continue
            }
            if (stopRequested.get()) return

            Files.deleteIfExists(layout.runtimeDir.resolve("lobby.pid"))
            Files.deleteIfExists(layout.runtimeDir.resolve("lobby.ready"))
            if (!awaitWake(request.lobbyRestartDelay.toMillis())) return
            if (stopRequested.get()) return
            lobbyProcess = launchLobby(command, request)
        }
    }

    private fun launchManagedBackend(
        name: BackendName,
        request: InfrastructureRequest,
        port: Int,
        workDir: Path,
    ): OwnedProcess {
        val command = request.backendCommand.ifEmpty {
            defaultJavaCommand(
                layout.binariesDir.resolve(
                    "paper-${PinnedRuntimeArtifactProvider.PAPER_VERSION}-${PinnedRuntimeArtifactProvider.PAPER_BUILD}.jar",
                ),
                "--nogui",
            )
        }
        val process = processSupervisor.launch(command, workDir)
        backendProcess = process
        val registration = BackendRegistration(name, port, request.backendOwner, OwnershipMode.MANAGED, process.identity)
        RegistryStore(layout).register(registration)
        awaitReadiness(process, request.backendReadinessHost, port, request.readinessTimeout, "backend $name")
        AtomicFiles.write(layout.backend(name).ready, "ready\n")
        return process
    }

    private fun cleanup(request: InfrastructureRequest) {
        stopRequested.set(true)
        synchronized(wakeMonitor) { wakeMonitor.notifyAll() }
        val interrupted = Thread.interrupted()
        try {
            val backend = backendProcess
            val backendResult = backend?.let { terminateSafely(it, request.shutdownTimeout) }
            val lobby = lobbyProcess
            val lobbyResult = lobby?.let { terminateSafely(it, request.shutdownTimeout) }
            val proxy = proxyProcess
            val proxyResult = proxy?.let { terminateSafely(it, request.shutdownTimeout) }

            val reservation = backendReservation
            if (reservation != null && backend != null && backendResult?.success == true) {
                val current = runCatching { RegistryStore(layout).readRegistration(reservation.name) }.getOrNull()
                // Never unregister an external claim, even if it reuses our owner string.
                if (current?.mode == OwnershipMode.MANAGED &&
                    current.owner == reservation.owner &&
                    current.port == reservation.port &&
                    current.process == backend.identity
                ) {
                    runCatching { RegistryStore(layout).unregister(reservation.name, reservation.owner) }
                }
            }

            if (proxyResult?.success == true) {
                Files.deleteIfExists(layout.proxyPid)
                Files.deleteIfExists(layout.proxyReady)
            }
            if (lobbyResult?.success == true) {
                Files.deleteIfExists(layout.runtimeDir.resolve("lobby.pid"))
                Files.deleteIfExists(layout.runtimeDir.resolve("lobby.ready"))
            }
            removeProvenOwnerState(
                removeProxy = proxyResult?.success == true,
                removeLobby = lobbyResult?.success == true,
                removeOwner = proxyResult?.success == true && lobbyResult?.success == true,
            )
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun terminateSafely(process: OwnedProcess, timeout: Duration): ProcessSupervisor.TerminationResult =
        runCatching { processSupervisor.terminate(process, timeout) }
            .getOrElse { ProcessSupervisor.TerminationResult.NOT_TERMINATED }

    private fun removeProvenOwnerState(removeProxy: Boolean, removeLobby: Boolean, removeOwner: Boolean) {
        if (removeOwner) {
            Files.deleteIfExists(layout.proxyOwner)
            return
        }
        if (!removeProxy && !removeLobby) return
        val content = AtomicFiles.readIfExists(layout.proxyOwner) ?: return
        val removeKeys = buildSet {
            if (removeProxy) addAll(CHILD_KEYS)
            if (removeLobby) addAll(LOBBY_KEYS)
        }
        val kept = content.lineSequence()
            .filter { line -> line.substringBefore('=') !in removeKeys }
            .toList()
        if (kept.isEmpty()) Files.deleteIfExists(layout.proxyOwner)
        else AtomicFiles.write(layout.proxyOwner, kept.joinToString("\n") + "\n")
    }

    private fun writeControllerOwner(owner: String, proxyPort: Int) {
        AtomicFiles.write(
            layout.proxyOwner,
            "owner=$owner\npid=${ProcessHandle.current().pid()}\nport=$proxyPort\nmode=infrastructure\n",
        )
    }

    private fun persistProxyProcess(identity: ProcessIdentity) {
        AtomicFiles.write(layout.proxyPid, "${identity.pid}\n")
        updateOwnerState(
            mapOf(
                "child-pid" to identity.pid.toString(),
                "child-start" to identity.startInstant.toString(),
                "child-executable" to identity.executable.toString(),
                "child-working-directory" to identity.workingDirectory.toString(),
            ),
        )
    }

    private fun persistLobbyProcess(identity: ProcessIdentity) {
        AtomicFiles.write(layout.runtimeDir.resolve("lobby.pid"), "${identity.pid}\n")
        updateOwnerState(
            mapOf(
                "lobby-pid" to identity.pid.toString(),
                "lobby-start" to identity.startInstant.toString(),
                "lobby-executable" to identity.executable.toString(),
                "lobby-working-directory" to identity.workingDirectory.toString(),
            ),
        )
    }

    private fun updateOwnerState(values: Map<String, String>) {
        val existing = AtomicFiles.readIfExists(layout.proxyOwner).orEmpty()
        val keys = values.keys
        val kept = existing.lineSequence()
            .filter { line -> line.substringBefore('=') !in keys }
            .filter { it.isNotEmpty() }
            .toMutableList()
        values.forEach { (key, value) -> kept += "$key=$value" }
        AtomicFiles.write(layout.proxyOwner, kept.joinToString("\n") + "\n")
    }

    private fun awaitReadiness(
        process: OwnedProcess,
        host: String,
        port: Int,
        timeout: Duration,
        component: String,
    ) {
        requireLive(process, component)
        val future = FutureTask<Unit> {
            readiness.await(host, port, timeout)
            Unit
        }
        Thread(future, "runtime-readiness-$component").apply {
            isDaemon = true
            start()
        }
        try {
            while (true) {
                if (stopRequested.get()) {
                    future.cancel(true)
                    throw InterruptedException("stop requested while waiting for $component readiness")
                }
                requireLive(process, component)
                try {
                    future.get(50, TimeUnit.MILLISECONDS)
                    requireLive(process, component)
                    return
                } catch (_: TimeoutException) {
                    // Revalidate the owned process before the next wait.
                } catch (error: ExecutionException) {
                    val cause = error.cause
                    when (cause) {
                        is RuntimeException -> throw cause
                        is Error -> throw cause
                        null -> throw IllegalStateException("$component readiness failed")
                        else -> throw IllegalStateException("$component readiness failed", cause)
                    }
                } catch (error: InterruptedException) {
                    future.cancel(true)
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }

    private fun requireLive(process: OwnedProcess, component: String) {
        if (!process.process.isAlive) throw IllegalStateException("$component process exited before readiness")
        check(processSupervisor.identityReader.matches(process.identity)) {
            "$component process identity no longer matches its owner lease"
        }
    }

    private fun identityIsLive(identity: ProcessIdentity): Boolean =
        processSupervisor.identityReader.matches(identity)

    private fun awaitWake(millis: Long): Boolean {
        if (stopRequested.get()) return false
        try {
            synchronized(wakeMonitor) {
                if (!stopRequested.get()) wakeMonitor.wait(millis.coerceAtLeast(1L))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            stopRequested.set(true)
            return false
        }
        return !stopRequested.get()
    }

    private fun resolvePorts(request: InfrastructureRequest): InfrastructureRequest {
        val proxyPort = resolvePort(request.proxyPort, "proxy")
        val lobbyPort = resolvePort(request.lobbyPort, "lobby")
        return request.copy(
            proxyPort = proxyPort,
            lobbyPort = lobbyPort,
            proxyReadinessPort = if (request.proxyReadinessPort == 0) proxyPort else request.proxyReadinessPort,
            lobbyReadinessPort = if (request.lobbyReadinessPort == 0) lobbyPort else request.lobbyReadinessPort,
        )
    }

    private fun resolvePort(port: Int, component: String): Int {
        if (port == 0) return ServerSocket(0).use { it.localPort }
        require(port in 1024..65535) { "$component port must be in 1024..65535 or 0: $port" }
        return port
    }

    private fun defaultJavaCommand(jar: Path, vararg args: String): List<String> =
        listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString(), *args)

    private fun validate(request: InfrastructureRequest) {
        require(request.owner.isNotBlank() && '\n' !in request.owner && '\r' !in request.owner) {
            "Infrastructure owner must be a single line"
        }
        require(request.proxyPort in 1024..65535) { "Proxy port must be in 1024..65535: ${request.proxyPort}" }
        require(request.lobbyPort in 1024..65535) { "Lobby port must be in 1024..65535: ${request.lobbyPort}" }
        require(request.proxyPort != request.lobbyPort) { "Proxy and lobby ports must differ" }
        require(request.targetServer.isNotBlank()) { "Target server must not be blank" }
        require(request.proxyReadinessHost.isNotBlank()) { "Proxy readiness host must not be blank" }
        require(request.lobbyReadinessHost.isNotBlank()) { "Lobby readiness host must not be blank" }
        require(request.backendReadinessHost.isNotBlank()) { "Backend readiness host must not be blank" }
        require(request.proxyReadinessPort in 1..65535) { "Proxy readiness port must be in 1..65535" }
        require(request.lobbyReadinessPort in 1..65535) { "Lobby readiness port must be in 1..65535" }
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

    private data class BackendReservation(
        val name: BackendName,
        val owner: String,
        val port: Int,
    )

    private class ProxyLockUnavailable : IllegalStateException()

    private companion object {
        val CHILD_KEYS = setOf(
            "child-pid",
            "child-start",
            "child-executable",
            "child-working-directory",
        )
        val LOBBY_KEYS = setOf(
            "lobby-pid",
            "lobby-start",
            "lobby-executable",
            "lobby-working-directory",
        )
    }
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
    val readinessHost: String = "127.0.0.1",
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
    @Volatile private var runThread: Thread? = null
    private val stopRequested = AtomicBoolean(false)
    private val wakeMonitor = Object()
    private var reservation: ManagedReservation? = null

    fun run(request: ManagedBackendRequest): Int {
        activeRequest = request
        runThread = Thread.currentThread()
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
            val paperJar = layout.binariesDir.resolve(
                "paper-${PinnedRuntimeArtifactProvider.PAPER_VERSION}-${PinnedRuntimeArtifactProvider.PAPER_BUILD}.jar",
            )
            if (request.paperCommand.isEmpty()) request.artifacts.paper(paperJar)
            paperWriter.writeManaged(request.workDir, PaperConfig(port))
            opsWriter.write(request.workDir, request.devUsers)
            check(preflight.verifyPaper(request.workDir, external = false).success) {
                preflight.verifyPaper(request.workDir, external = false).message
            }
            registry.register(BackendRegistration(name, port, request.owner, OwnershipMode.MANAGED, null))
            reservation = ManagedReservation(name, request.owner, port)
            val command = request.paperCommand.ifEmpty {
                listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", paperJar.toString(), "--nogui")
            }
            val process = processSupervisor.launch(command, request.workDir)
            // Retain the exact process handle before registration/readiness can fail.
            active = process
            registry.register(BackendRegistration(name, port, request.owner, OwnershipMode.MANAGED, process.identity))
            awaitReadiness(process, request.readinessHost, port, request.readinessTimeout)
            AtomicFiles.write(state.ready, "ready\n")
            while (process.process.isAlive && !stopRequested.get()) {
                awaitWake(50)
            }
            0
        } catch (_: InterruptedException) {
            if (stopRequested.get()) 0 else 1
        } catch (error: Exception) {
            System.err.println("managed backend ${request.name}: ${error.message ?: error::class.simpleName}")
            1
        } finally {
            cleanup(request)
            runThread = null
            active = null
            activeRequest = null
            reservation = null
            stopRequested.set(false)
        }
    }

    fun stop(request: ManagedBackendRequest = activeRequest ?: error("No managed backend is running")): Int {
        stopRequested.set(true)
        synchronized(wakeMonitor) { wakeMonitor.notifyAll() }
        runThread?.takeIf { it !== Thread.currentThread() }?.interrupt()
        return 0
    }

    private fun cleanup(request: ManagedBackendRequest) {
        val name = runCatching { BackendName(request.name) }.getOrNull() ?: return
        val cleanupInterrupted = Thread.interrupted()
        try {
            val process = active
            val result = process?.let {
                runCatching { processSupervisor.terminate(it, request.shutdownTimeout) }
                    .getOrElse { ProcessSupervisor.TerminationResult.NOT_TERMINATED }
            }
            if (result?.success == true) Files.deleteIfExists(layout.backend(name).ready)
            val claim = reservation ?: return
            // A stop interrupt is consumed above and must not interrupt the
            // registration lock; any unproven termination still preserves state.
            Thread.interrupted()
            val current = runCatching { RegistryStore(layout).readRegistration(name) }.getOrNull()
            if (current?.mode == OwnershipMode.MANAGED &&
                current.owner == claim.owner &&
                current.port == claim.port &&
                ((process != null && result?.success == true && current.process == process.identity) ||
                    (process == null && current.process == null))
            ) {
                runCatching { RegistryStore(layout).unregister(name, claim.owner) }
            }
        } finally {
            if (cleanupInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun awaitReadiness(process: OwnedProcess, host: String, port: Int, timeout: Duration) {
        requireLive(process)
        val future = FutureTask<Unit> {
            readiness.await(host, port, timeout)
            Unit
        }
        Thread(future, "runtime-readiness-backend-${process.pid}").apply {
            isDaemon = true
            start()
        }
        try {
            while (true) {
                if (stopRequested.get()) {
                    future.cancel(true)
                    throw InterruptedException("stop requested while waiting for backend readiness")
                }
                requireLive(process)
                try {
                    future.get(50, TimeUnit.MILLISECONDS)
                    requireLive(process)
                    return
                } catch (_: TimeoutException) {
                    // Keep checking the identity-owned child.
                } catch (error: ExecutionException) {
                    throw (error.cause as? RuntimeException)
                        ?: IllegalStateException("backend readiness failed", error.cause)
                } catch (error: InterruptedException) {
                    future.cancel(true)
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }

    private fun requireLive(process: OwnedProcess) {
        check(process.process.isAlive) { "backend process exited before readiness" }
        check(processSupervisor.identityReader.matches(process.identity)) {
            "backend process identity no longer matches its owner lease"
        }
    }

    private fun awaitWake(millis: Long) {
        synchronized(wakeMonitor) {
            if (!stopRequested.get()) wakeMonitor.wait(millis.coerceAtLeast(1L))
        }
    }
    private data class ManagedReservation(
        val name: BackendName,
        val owner: String,
        val port: Int,
    )
}
// Compatibility aliases keep request names available beside controllers.
typealias ReloadNetworkRequest = io.github.developmentnetwork.runtime.service.ReloadNetworkRequest
typealias StopNetworkRequest = io.github.developmentnetwork.runtime.service.StopNetworkRequest
typealias RestartBackendRequest = io.github.developmentnetwork.runtime.service.RestartBackendRequest
