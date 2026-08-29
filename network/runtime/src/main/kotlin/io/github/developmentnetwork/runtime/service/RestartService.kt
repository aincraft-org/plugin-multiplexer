package io.github.developmentnetwork.runtime.service

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
) {
    fun execute(request: RestartBackendRequest): Int {
        return try {
            val name = BackendName(request.name)
            val current = registry.readRegistration(name) ?: error("Backend $name is not registered")
            require(current.mode == OwnershipMode.MANAGED) { "Backend $name is external; external processes are not restartable" }
            require(current.owner == request.owner) { "Backend $name is owned by ${current.owner}, not ${request.owner}" }
            val persistedPort = current.port
            require(request.port == null || request.port == persistedPort) { "Restart must retain persisted port $persistedPort" }
            val identity = current.process ?: error("Managed backend $name has no process identity")
            require(identityReader.matches(identity)) { "Managed backend $name process identity is not live or no longer owned" }
            val oldHandle = ProcessHandle.of(identity.pid).orElse(null) ?: error("Managed backend process disappeared")
            require(oldHandle.destroy()) { "Unable to stop managed backend $name" }
            waitDead(oldHandle, request.shutdownTimeout)
            Files.deleteIfExists(layout.backend(name).ready)
            request.pluginJar?.let { deployPlugin(it, request.workDir ?: layout.base.resolve("runtime/auto/${name.value}")) }
            val workDir = request.workDir ?: layout.base.resolve("runtime/auto/${name.value}")
            Files.createDirectories(workDir)
            val jar = layout.binariesDir.resolve("paper-${io.github.developmentnetwork.runtime.controller.PinnedRuntimeArtifactProvider.PAPER_VERSION}-${io.github.developmentnetwork.runtime.controller.PinnedRuntimeArtifactProvider.PAPER_BUILD}.jar")
            if (request.paperCommand.isEmpty()) request.artifacts.paper(jar)
            val command = request.paperCommand.ifEmpty { listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString(), "--nogui") }
            val replacement = processSupervisor.launch(command, workDir)
            registry.register(BackendRegistration(name, persistedPort, request.owner, OwnershipMode.MANAGED, replacement.identity))
            readiness.await(request.readinessHost, persistedPort, request.readinessTimeout)
            AtomicFiles.write(layout.backend(name).ready, "ready\n")
            0
        } catch (error: Exception) {
            System.err.println("backend restart: ${error.message ?: error::class.simpleName}")
            1
        }
    }

    private fun deployPlugin(plugin: Path, workDir: Path) {
        require(Files.isRegularFile(plugin)) { "Plugin artifact does not exist: $plugin" }
        val plugins = workDir.resolve("plugins")
        Files.createDirectories(plugins)
        Files.list(plugins).use { entries -> entries.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.forEach { Files.deleteIfExists(it) } }
        Files.copy(plugin, plugins.resolve(plugin.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun waitDead(handle: ProcessHandle, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos().coerceAtLeast(0)
        while (handle.isAlive && System.nanoTime() < deadline) Thread.sleep(25)
        if (handle.isAlive && identityReader.matches(io.github.developmentnetwork.runtime.model.ProcessIdentity(handle.pid(), handle.info().startInstant().orElse(null), handle.info().command().orElse(null)?.let(Path::of), null))) {
            handle.destroyForcibly()
        }
        require(!handle.isAlive) { "Managed backend did not stop before deadline" }
    }
}

fun interface RestartReadiness {
    fun await(host: String, port: Int, timeout: Duration)
}
