package io.github.developmentnetwork.runtime.service

import io.github.developmentnetwork.runtime.config.OfflinePreflight
import io.github.developmentnetwork.runtime.controller.RuntimeArtifactProvider
import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.model.BackendRegistration
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.process.ProcessIdentityReader
import io.github.developmentnetwork.runtime.process.ProcessSupervisor
import io.github.developmentnetwork.runtime.process.ReadinessProbe
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/** Restart exactly one managed backend while retaining its persisted port. */
data class RestartBackendRequest(
    val name: String,
    val owner: String,
    val port: Int? = null,
    val workDir: Path? = null,
    val paperCommand: List<String> = emptyList(),
    val pluginJar: Path? = null,
    val artifacts: RuntimeArtifactProvider = io.github.developmentnetwork.runtime.controller.PinnedRuntimeArtifactProvider(),
    val readinessHost: String = "localhost",
    val readinessTimeout: Duration = Duration.ofSeconds(30),
    val shutdownTimeout: Duration = Duration.ofSeconds(30),
)

class RestartService(
    private val layout: RuntimeLayout,
    private val registry: RegistryStore = RegistryStore(layout),
    private val processSupervisor: ProcessSupervisor = ProcessSupervisor(),
    private val identityReader: ProcessIdentityReader = ProcessIdentityReader(),
    private val readiness: RestartReadiness = RestartReadiness { host, port, timeout -> ReadinessProbe().await(host, port, timeout) },
    private val preflight: OfflinePreflight = OfflinePreflight(),
) {
    fun execute(request: RestartBackendRequest): Int {
        return try {
            val name = BackendName(request.name)
            registry.withRegistrationTransition {
                val current = readRegistration(name) ?: error("Backend $name is not registered")
                require(current.mode == OwnershipMode.MANAGED) {
                    "Backend $name is external; external processes are not restartable"
                }
                require(current.owner == request.owner) {
                    "Backend $name is owned by ${current.owner}, not ${request.owner}"
                }
                val persistedPort = current.port
                require(request.port == null || request.port == persistedPort) {
                    "Restart must retain persisted port $persistedPort"
                }
                val oldIdentity = current.process ?: error("Managed backend $name has no process identity")
                requireCompleteIdentity(oldIdentity, "Managed backend $name")
                require(identityReader.matches(oldIdentity)) {
                    "Managed backend $name process identity is not live or no longer owned"
                }
                val workDir = request.workDir ?: oldIdentity.workingDirectory
                    ?: error("Managed backend $name has no persisted working directory")
                if (request.workDir != null) {
                    require(normalize(request.workDir) == normalize(workDir)) {
                        "Restart must retain persisted working directory $workDir"
                    }
                }
                require(Files.isDirectory(workDir)) { "Managed backend working directory does not exist: $workDir" }

                // All checks and artifact preparation happen before any stop, so a bad
                // config, missing plugin, or failed download does not cause an outage.
                require(preflight.verifyPaper(workDir, external = false).success) {
                    preflight.verifyPaper(workDir, external = false).message
                }
                val paperJar = layout.binariesDir.resolve(
                    "paper-${io.github.developmentnetwork.runtime.controller.PinnedRuntimeArtifactProvider.PAPER_VERSION}-${io.github.developmentnetwork.runtime.controller.PinnedRuntimeArtifactProvider.PAPER_BUILD}.jar",
                )
                if (request.paperCommand.isEmpty()) request.artifacts.paper(paperJar)
                val oldReady = capture(layout.backend(name).ready)
                val oldPlugins = capturePlugins(workDir)
                var replacement: io.github.developmentnetwork.runtime.process.OwnedProcess? = null
                var replacementRegistered = false
                try {
                    require(terminateVerified(oldIdentity, request.shutdownTimeout)) {
                        "Unable to stop managed backend $name"
                    }
                    Files.deleteIfExists(layout.backend(name).ready)
                    request.pluginJar?.let { deployPlugin(it, workDir) }
                    Files.createDirectories(workDir)
                    val command = request.paperCommand.ifEmpty {
                        listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", paperJar.toString(), "--nogui")
                    }
                    replacement = processSupervisor.launch(command, workDir)
                    val replacementIdentity = replacement.identity
                    requireCompleteIdentity(replacementIdentity, "Replacement backend $name")
                    require(identityReader.matches(replacementIdentity)) {
                        "Replacement backend $name process identity could not be verified"
                    }
                    val candidate = BackendRegistration(name, persistedPort, request.owner, OwnershipMode.MANAGED, replacementIdentity)
                    register(candidate)
                    replacementRegistered = true
                    readiness.await(request.readinessHost, persistedPort, request.readinessTimeout)
                    require(readRegistration(name) == candidate) {
                        "Backend $name ownership changed while waiting for readiness"
                    }
                    require(identityReader.matches(replacementIdentity)) {
                        "Replacement backend $name process identity changed before readiness"
                    }
                    AtomicFiles.write(layout.backend(name).ready, "ready\n")
                    0
                } catch (error: Exception) {
                    replacement?.let { process ->
                        if (identityReader.matches(process.identity)) terminateVerified(process.identity, request.shutdownTimeout)
                    }
                    if (replacementRegistered && readRegistration(name)?.owner == request.owner) {
                        register(current)
                    }
                    restore(layout.backend(name).ready, oldReady)
                    restorePlugins(workDir, oldPlugins)
                    throw error
                }
            }
        } catch (error: Exception) {
            System.err.println("backend restart: ${error.message ?: error::class.simpleName}")
            1
        }
    }

    private fun requireCompleteIdentity(identity: io.github.developmentnetwork.runtime.model.ProcessIdentity, subject: String) {
        require(identity.startInstant != null && identity.executable != null && identity.workingDirectory != null) {
            "$subject has incomplete process identity"
        }
    }

    private fun terminateVerified(identity: io.github.developmentnetwork.runtime.model.ProcessIdentity, timeout: Duration): Boolean {
        if (!identityReader.matches(identity)) return false
        val handle = runCatching { ProcessHandle.of(identity.pid).orElse(null) }.getOrNull() ?: return false
        if (!handle.isAlive || !runCatching { handle.destroy() }.getOrDefault(false)) return false
        val deadline = deadline(timeout)
        while (handle.isAlive && System.nanoTime() < deadline) {
            try { Thread.sleep(25) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (!handle.isAlive) return true
        if (!identityReader.matches(identity)) return false
        if (!runCatching { handle.destroyForcibly() }.getOrDefault(false)) return false
        val forceDeadline = deadline(Duration.ofSeconds(2))
        while (handle.isAlive && System.nanoTime() < forceDeadline) {
            try { Thread.sleep(25) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !handle.isAlive
    }

    private fun deployPlugin(plugin: Path, workDir: Path) {
        require(Files.isRegularFile(plugin)) { "Plugin artifact does not exist: $plugin" }
        val plugins = workDir.resolve("plugins")
        Files.createDirectories(plugins)
        Files.list(plugins).use { entries ->
            entries.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .forEach { Files.deleteIfExists(it) }
        }
        Files.copy(plugin, plugins.resolve(plugin.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun capture(path: Path): ByteArray? = if (Files.exists(path)) Files.readAllBytes(path) else null

    private fun restore(path: Path, content: ByteArray?) {
        if (content == null) Files.deleteIfExists(path) else AtomicFiles.write(path, content.toString(Charsets.UTF_8))
    }

    private fun capturePlugins(workDir: Path): Map<Path, ByteArray> {
        val plugins = workDir.resolve("plugins")
        if (!Files.isDirectory(plugins)) return emptyMap()
        return Files.list(plugins).use { entries ->
            entries.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .associateWith { Files.readAllBytes(it) }
        }
    }

    private fun restorePlugins(workDir: Path, plugins: Map<Path, ByteArray>) {
        val directory = workDir.resolve("plugins")
        Files.createDirectories(directory)
        Files.list(directory).use { entries ->
            entries.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .forEach { Files.deleteIfExists(it) }
        }
        plugins.forEach { (path, bytes) -> Files.write(path, bytes) }
    }

    private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

    private fun deadline(timeout: Duration): Long {
        val nanos = runCatching { timeout.toNanos() }.getOrDefault(Long.MAX_VALUE).coerceAtLeast(0)
        val now = System.nanoTime()
        return if (nanos > Long.MAX_VALUE - now) Long.MAX_VALUE else now + nanos
    }
}

fun interface RestartReadiness {
    fun await(host: String, port: Int, timeout: Duration)
}
