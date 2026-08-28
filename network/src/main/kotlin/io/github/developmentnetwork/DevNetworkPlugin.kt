package io.github.developmentnetwork

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.TaskAction

/**
 * Gradle integration for the development network.
 *
 * Shared mode:
 *   runProxy       owns the proxy + lobby controller.
 *   registerBackend attaches an already-running external Paper server.
 *   runBackend     builds and owns this project's managed Paper backend.
 *
 * runNetwork remains a one-project convenience task that starts the full
 * stack. It is intentionally not the coordination primitive for multiple
 * plugin projects sharing a networkBase.
 */
class DevNetworkPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val jarTaskName = project.findProperty("networkJarTask") as String? ?: "jar"

        project.tasks.register("runProxy", RunProxyTask::class.java) { task ->
            task.group = "network"
            task.description = "Own and run the shared Velocity proxy plus lobby"
        }

        project.tasks.register("registerBackend", RegisterBackendTask::class.java) { task ->
            task.group = "network"
            task.description = "Attach an already-running backend (never starts or stops Paper)"
        }

        project.tasks.register("unregisterBackend", UnregisterBackendTask::class.java) { task ->
            task.group = "network"
            task.description = "Remove this project's external backend registration"
        }

        project.tasks.register("runBackend", RunBackendTask::class.java) { task ->
            task.group = "network"
            task.description = "Build, register, and run this project's managed Paper backend"
            task.dependsOn(jarTaskName)
        }

        project.tasks.register("runNetwork", RunNetworkTask::class.java) { task ->
            task.group = "network"
            task.description = "Run a one-project full network (proxy + lobby + backend)"
            task.dependsOn(jarTaskName)
        }
    }
}

abstract class RunProxyTask : DefaultTask() {
    @TaskAction
    fun runProxy() {
        val project = project
        val base = property(project, "networkBase") ?: "run/network"
        val proxyPort = resolveProxyPort(project)
        val harnessBin = harnessBin(project)

        val process = launch(
            project,
            listOf(harnessBin.resolve("dev-network.sh").absolutePath),
            mapOf(
                "BASE" to project.layout.projectDirectory.dir(base).asFile.absolutePath,
                "NETWORK_ROLE" to "proxy",
                "PROXY_PORT" to proxyPort.toString(),
                "DEV_USERS" to devUsers(project)
            ) + proxyOnlineModeEnvironment(project),
            removeInherited = setOf("BACKENDS", "EXTERNAL_BACKENDS")
        )
        val exit = waitFor(process, "proxy controller")
        if (exit != 0 && exit != 130) {
            throw GradleException("proxy controller exited with code $exit")
        }
    }
}

abstract class RegisterBackendTask : DefaultTask() {
    @TaskAction
    fun registerBackend() {
        val project = project
        val base = property(project, "networkBase") ?: "run/network"
        val name = property(project, "networkBackend") ?: project.name
        check(name.matches(Regex("[A-Za-z0-9_-]+"))) {
            "networkBackend '$name' invalid (use [A-Za-z0-9_-]+)"
        }
        val port = property(project, "networkBackendPort")?.toIntOrNull()
            ?: throw GradleException(
                "registerBackend requires -PnetworkBackendPort=<port>; " +
                    "it attaches an already-running external Paper server"
            )
        check(port in 1024..65535) {
            "networkBackendPort '$port' invalid (use 1024..65535)"
        }

        val baseDir = project.layout.projectDirectory.dir(base).asFile
        val harness = harnessBin(project)
        val ownerId = registrationOwner(project, name)
        println("== registerBackend: attaching '$name' on port $port (owner $ownerId)")

        val registration = launch(
            project,
            listOf(
                harness.resolve("register-backend.sh").absolutePath,
                name,
                port.toString()
            ),
            mapOf(
                "BASE" to baseDir.absolutePath,
                "REGISTRATION_OWNER" to ownerId
            ),
            removeInherited = setOf("BACKENDS", "EXTERNAL_BACKENDS")
        )
        val exit = waitFor(registration, "external backend registration")
        if (exit != 0) {
            throw GradleException("external backend registration exited with code $exit")
        }
        println("== registerBackend: '$name' attached; Paper was not started or stopped")
    }
}

abstract class UnregisterBackendTask : DefaultTask() {
    @TaskAction
    fun unregisterBackend() {
        val project = project
        val base = property(project, "networkBase") ?: "run/network"
        val name = property(project, "networkBackend") ?: project.name
        check(name.matches(Regex("[A-Za-z0-9_-]+"))) {
            "networkBackend '$name' invalid (use [A-Za-z0-9_-]+)"
        }

        val baseDir = project.layout.projectDirectory.dir(base).asFile
        val harness = harnessBin(project)
        val ownerId = registrationOwner(project, name)
        val unregister = launch(
            project,
            listOf(
                harness.resolve("unregister-backend.sh").absolutePath,
                name
            ),
            mapOf(
                "BASE" to baseDir.absolutePath,
                "REGISTRATION_OWNER" to ownerId
            ),
            removeInherited = setOf("BACKENDS", "EXTERNAL_BACKENDS")
        )
        val exit = waitFor(unregister, "backend unregistration")
        if (exit != 0) {
            throw GradleException("backend unregistration exited with code $exit")
        }
    }
}

abstract class RunBackendTask : DefaultTask() {
    @TaskAction
    fun runBackend() {
        val project = project
        val base = property(project, "networkBase") ?: "run/network"
        val name = property(project, "networkBackend") ?: project.name
        check(name.matches(Regex("[A-Za-z0-9_-]+"))) {
            "networkBackend '$name' invalid (use [A-Za-z0-9_-]+)"
        }

        val baseDir = project.layout.projectDirectory.dir(base).asFile
        val harness = harnessBin(project)
        deployBackendJar(project, baseDir, name)
        val serverDir = baseDir.resolve("runtime/auto/$name")
        val ownerId = managedRegistrationOwner(project, name)

        println("== runBackend: backend '$name' -> $serverDir (owner $ownerId)")

        val cleaned = AtomicBoolean(false)
        val cleanup = {
            if (cleaned.compareAndSet(false, true)) {
                try {
                    val cleanupProcess = launch(
                        project,
                        listOf(
                            harness.resolve("unregister-backend.sh").absolutePath,
                            name,
                            "--stop"
                        ),
                        mapOf(
                            "BASE" to baseDir.absolutePath,
                            "REGISTRATION_OWNER" to ownerId
                        ),
                        removeInherited = setOf("BACKENDS", "EXTERNAL_BACKENDS")
                    )
                    val cleanupExit = waitFor(cleanupProcess, "managed backend cleanup")
                    if (cleanupExit != 0) {
                        println("!! runBackend: cleanup exited with code $cleanupExit")
                    }
                } catch (error: Exception) {
                    println("!! runBackend: cleanup failed: ${error.message}")
                }
            }
        }
        val shutdownHook = Thread { cleanup() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            val registration = launch(
                project,
                listOf(
                    harness.resolve("register-backend.sh").absolutePath,
                    name,
                    "",
                    serverDir.absolutePath
                ),
                mapOf(
                    "BASE" to baseDir.absolutePath,
                    "DEV_USERS" to devUsers(project),
                    "REGISTRATION_OWNER" to ownerId
                ),
                removeInherited = setOf("BACKENDS", "EXTERNAL_BACKENDS")
            )
            val registrationExit = waitFor(registration, "managed backend registration")
            if (registrationExit != 0) {
                throw GradleException("managed backend registration exited with code $registrationExit")
            }

            val pidFile = baseDir.resolve("runtime/$name.pid")
            val pid = waitForPid(pidFile, name)
            waitForBackend(pid, name)
        } finally {
            cleanup()
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // JVM shutdown is already in progress; the hook is running.
            }
        }
    }
}

abstract class RunNetworkTask : DefaultTask() {
    @TaskAction
    fun runNetwork() {
        val project = project
        val base = property(project, "networkBase") ?: "run/network"
        val name = property(project, "networkBackend") ?: project.name
        check(name.matches(Regex("[A-Za-z0-9_-]+"))) {
            "networkBackend '$name' invalid (use [A-Za-z0-9_-]+)"
        }

        val baseDir = project.layout.projectDirectory.dir(base).asFile
        val harnessBin = harnessBin(project)
        deployBackendJar(project, baseDir, name)

        val proxyPort = resolveProxyPort(project)
        val users = devUsers(project)
        println("== runNetwork: standalone backend '$name'; proxy -> $proxyPort; ops -> $users (BASE=$base)")

        val process = launch(
            project,
            listOf(harnessBin.resolve("dev-network.sh").absolutePath),
            mapOf(
                "BASE" to baseDir.absolutePath,
                "BACKENDS" to name,
                "PROXY_PORT" to proxyPort.toString(),
                "DEV_USERS" to users,
                "NETWORK_ROLE" to "full"
            ) + proxyOnlineModeEnvironment(project)
        )
        val exit = waitFor(process, "full network")
        if (exit != 0 && exit != 130) {
            throw GradleException("dev network exited with code $exit")
        }
    }
}

private fun property(project: Project, name: String): String? =
    project.findProperty(name) as String?

private fun devUsers(project: Project): String =
    property(project, "networkDevUsers")
        ?: System.getenv("DEV_NETWORK_DEV_USERS")
        ?: "dev"

private fun proxyOnlineModeEnvironment(project: Project): Map<String, String> {
    val value = property(project, "networkOnlineMode") ?: return emptyMap()
    check(value == "true" || value == "false") {
        "networkOnlineMode '$value' invalid (use true or false)"
    }
    return mapOf("PROXY_ONLINE_MODE" to value)
}

private fun harnessBin(project: Project): File {
    val resolved = property(project, "devNetworkBin")
        ?.let { project.file(it).absolutePath }
        ?: System.getenv("DEV_NETWORK_BIN")
        ?: System.getenv("DEV_NETWORK_DIR")?.let { project.file("$it/bin").absolutePath }
        ?: project.gradle.includedBuilds
            .asSequence()
            .map { it.projectDir.resolve("bin") }
            .firstOrNull { it.resolve("dev-network.sh").isFile }
            ?.absolutePath
        ?: project.rootProject.projectDir.resolve("development-network/bin")
            .takeIf { it.isDirectory }
            ?.absolutePath
        ?: project.rootProject.projectDir.parentFile
            .resolve("server-development-skills/development-network/bin")
            .takeIf { it.isDirectory }
            ?.absolutePath

    check(resolved != null && project.file("$resolved/dev-network.sh").isFile) {
        "development-network harness bin not found — set -PdevNetworkBin, \$DEV_NETWORK_BIN, \$DEV_NETWORK_DIR, or include this build with a composite build"
    }
    return project.file(resolved)
}

private fun deployBackendJar(project: Project, baseDir: File, name: String) {
    val pluginsDir = baseDir.resolve("runtime/auto/$name/plugins")
    pluginsDir.mkdirs()

    // Stale CalVer jars accumulate; deploy only the fresh one.
    pluginsDir.listFiles { file -> file.extension == "jar" }?.forEach { it.delete() }

    val jarTaskName = property(project, "networkJarTask") ?: "jar"
    val jarTask = project.tasks.named(jarTaskName).get()
    val jarFile = (jarTask as? Jar)?.archiveFile?.get()?.asFile
        ?: error("task '$jarTaskName' is not a Jar task — set -PnetworkJarTask to a Jar task")
    check(jarFile.isFile) { "missing $jarFile — run $jarTaskName first" }

    val deployed = pluginsDir.resolve(jarFile.name)
    jarFile.copyTo(deployed, overwrite = true)
}

private fun resolveProxyPort(project: Project): Int {
    val configured = property(project, "networkProxyPort")?.toIntOrNull() ?: 25565
    check(configured == 0 || configured in 1024..65535) {
        "networkProxyPort '$configured' invalid (use 0 or 1024..65535)"
    }
    return if (configured == 0) findFreePort(25565) else configured
}

private fun findFreePort(start: Int): Int {
    var port = start
    while (port < 65535) {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
            return port
        } catch (_: java.io.IOException) {
            port++
        } finally {
            socket.close()
        }
    }
    throw GradleException("no free port found from $start")
}

private fun registrationOwner(project: Project, name: String): String {
    val configured = property(project, "networkRegistrationOwner")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (configured != null) {
        return configured
    }
    val projectId = "${project.path}:$name".replace(Regex("[^A-Za-z0-9_.:/-]"), "_")
    return "gradle-$projectId"
}

private fun managedRegistrationOwner(project: Project, name: String): String =
    "${registrationOwner(project, name)}-${ProcessHandle.current().pid()}-${System.nanoTime()}"

private fun launch(
    project: Project,
    command: List<String>,
    environment: Map<String, String>,
    removeInherited: Set<String> = emptySet()
): Process {
    val builder = ProcessBuilder(command)
        .directory(project.rootProject.projectDir)
        .inheritIO()
    val processEnvironment = builder.environment()
    removeInherited.forEach { processEnvironment.remove(it) }
    processEnvironment.putAll(environment)
    return builder.start()
}

private fun waitFor(process: Process, label: String): Int =
    try {
        process.waitFor()
    } catch (error: InterruptedException) {
        process.destroy()
        Thread.currentThread().interrupt()
        throw GradleException("$label interrupted", error)
    }

private fun waitForPid(pidFile: File, name: String): Long {
    val deadline = System.nanoTime() + 300_000_000_000L
    while (System.nanoTime() < deadline) {
        val pid = if (pidFile.isFile) pidFile.readText().trim().toLongOrNull() else null
        if (pid != null) {
            return pid
        }
        try {
            Thread.sleep(250)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GradleException("waiting for backend '$name' was interrupted", error)
        }
    }
    throw GradleException("backend '$name' did not create $pidFile within 300s")
}

private fun waitForBackend(pid: Long, name: String) {
    while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
        try {
            Thread.sleep(1000)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GradleException("waiting for backend '$name' was interrupted", error)
        }
    }
}