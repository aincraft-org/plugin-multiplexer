package io.github.developmentnetwork

import io.github.developmentnetwork.runtime.RuntimeCommand
import io.github.developmentnetwork.runtime.controller.InfrastructureRequest
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar

/** Runtime process bridge shared by all nine Gradle task adapters. */
object NetworkTaskSupport {
    @JvmStatic
    fun run(project: Project, command: RuntimeCommand, longLived: Boolean): Int {
        val extension = project.extensions.getByType(DevelopmentNetworkExtension::class.java)
        val process = try {
            RuntimeArtifactLauncher.launch(
                project.projectDir,
                project.gradle.gradleUserHomeDir,
                command.toArguments(extension),
            )
        } catch (error: Exception) {
            throw GradleException("Unable to launch embedded development network runtime", error)
        }
        val exit = try {
            process.waitFor()
        } catch (error: InterruptedException) {
            if (longLived) requestRuntimeShutdown(project, extension, command)
            val gracefulWait = cleanupWaitSeconds(extension)
            try {
                if (!process.waitFor(gracefulWait, TimeUnit.SECONDS)) {
                    process.destroy()
                    if (!process.waitFor(gracefulWait, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(gracefulWait, TimeUnit.SECONDS)
                    }
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
            } finally {
                Thread.currentThread().interrupt()
            }
            throw GradleException("${if (longLived) "network runtime" else "network operation"} interrupted", error)
        }
        if (exit != 0) throw GradleException("network runtime exited with code $exit")
        return exit
    }

    private fun requestRuntimeShutdown(
        project: Project,
        extension: DevelopmentNetworkExtension,
        command: RuntimeCommand,
    ): Boolean {
        val owner = when (command) {
            is RuntimeCommand.ServeProxy -> command.request.owner
            is RuntimeCommand.ServeFull -> command.request.owner
            is RuntimeCommand.ServeBackend -> command.request.owner
            else -> return false
        }
        return runCatching {
            val shutdown = RuntimeArtifactLauncher.launch(
                project.projectDir,
                project.gradle.gradleUserHomeDir,
                listOf(
                    "stopNetwork",
                    "--base=${extension.networkBase.get().asFile.absolutePath}",
                    "--owner=$owner",
                    "--managed-only=${command is RuntimeCommand.ServeBackend}",
                    "--control-timeout=${extension.networkControlTimeout.get()}",
                    "--shutdown-timeout=${extension.networkShutdownTimeout.get()}",
                ),
            )
            val waitSeconds = cleanupWaitSeconds(extension)
            if (!shutdown.waitFor(waitSeconds, TimeUnit.SECONDS)) {
                shutdown.destroy()
                if (!shutdown.waitFor(waitSeconds, TimeUnit.SECONDS)) shutdown.destroyForcibly()
            }
            shutdown.waitFor(1, TimeUnit.SECONDS) && shutdown.exitValue() == 0
        }.getOrDefault(false)
    }
    private fun cleanupWaitSeconds(extension: DevelopmentNetworkExtension): Long =
        extension.networkShutdownTimeout.get().coerceAtLeast(1L)


    private fun RuntimeCommand.toArguments(extension: DevelopmentNetworkExtension): List<String> {
        val args = mutableListOf(commandName())
        args += "--base=${extension.networkBase.get().asFile.absolutePath}"
        when (this) {
            is RuntimeCommand.ServeProxy -> args += infrastructureArguments(request, extension)
            is RuntimeCommand.ServeFull -> {
                args += infrastructureArguments(request, extension)
                request.backendName?.let { args += "--name=$it" }
                request.backendPort?.let { args += "--backend-port=$it" }
                request.backendWorkDir?.let { args += "--backend-dir=$it" }
                request.backendPluginJar?.let { args += "--plugin-jar=$it" }
            }
            is RuntimeCommand.ServeBackend -> {
                args += "--name=${request.name}"
                request.port?.let { args += "--backend-port=$it" }
                args += "--backend-dir=${request.workDir}"
                args += "--owner=${request.owner}"
                request.proxyOwner?.let { args += "--proxy-owner=$it" }
                args += "--proxy-port=${request.proxyPort}"
                args += "--lobby-port=${request.lobbyPort}"
                args += "--timeout=${request.readinessTimeout.seconds}"
                args += "--shutdown-timeout=${request.shutdownTimeout.seconds}"
                request.pluginJar?.let { args += "--plugin-jar=$it" }
                args += "--dev-users=${request.devUsers.joinToString(",")}"
            }
            is RuntimeCommand.RegisterExternal -> {
                args += "--name=${request.name}"
                args += "--port=${request.port}"
                args += "--registration-owner=${request.owner}"
                args += "--server-dir=${request.serverDir}"
                args += "--host=${request.host}"
                args += "--target-server=${request.targetServer}"
                args += "--timeout=${request.readinessTimeout.seconds}"
                args += "--lobby-port=${request.lobbyPort}"
                args += "--control-timeout=${request.controlTimeout.seconds}"
            }
            is RuntimeCommand.UnregisterExternal -> {
                args += "--name=${request.name}"
                args += "--registration-owner=${request.owner}"
                args += "--target-server=${request.targetServer}"
                args += "--lobby-port=${request.lobbyPort}"
                args += "--control-timeout=${request.controlTimeout.seconds}"
            }
            is RuntimeCommand.StopNetwork -> {
                request.owner?.let { args += "--owner=$it" }
                if (request.managedOnly) args += "--managed-only=true"
                args += "--control-timeout=${request.controlTimeout.seconds}"
                args += "--shutdown-timeout=${request.shutdownTimeout.seconds}"
            }
            is RuntimeCommand.ReloadNetwork -> {
                args += "--target-server=${request.targetServer}"
                args += "--proxy-port=${request.proxyPort}"
                args += "--online-mode=${request.onlineMode}"
                args += "--lobby-port=${request.lobbyPort}"
                args += "--control-timeout=${request.controlTimeout.seconds}"
            }
            is RuntimeCommand.RestartBackend -> {
                args += "--name=${request.name}"
                args += "--registration-owner=${request.owner}"
                request.port?.let { args += "--backend-port=$it" }
                request.workDir?.let { args += "--backend-dir=$it" }
                request.pluginJar?.let { args += "--plugin-jar=$it" }
                args += "--timeout=${request.readinessTimeout.seconds}"
                args += "--shutdown-timeout=${request.shutdownTimeout.seconds}"
            }
            is RuntimeCommand.NetworkStatus -> {
                args += "--host=${request.host}"
                request.proxyPort?.let { args += "--proxy-port=$it" }
                args += "--lobby-port=${request.lobbyPort}"
                args += "--timeout=${request.timeout.seconds}"
            }
        }
        return args
    }

    private fun infrastructureArguments(request: InfrastructureRequest, extension: DevelopmentNetworkExtension): List<String> = buildList {
        add("--proxy-port=${request.proxyPort}")
        add("--lobby-port=${request.lobbyPort}")
        add("--target-server=${request.targetServer}")
        add("--online-mode=${request.onlineMode}")
        add("--owner=${request.owner}")
        add("--timeout=${request.readinessTimeout.seconds}")
        add("--shutdown-timeout=${request.shutdownTimeout.seconds}")
        add("--dev-users=${request.devUsers.joinToString(",")}")
        extension.networkLobbyMapUrl.orNull?.let { add("--lobby-map-url=$it") }
        extension.networkLobbyMapSha256.orNull?.let { add("--lobby-map-sha256=$it") }
        extension.networkLobbyMapRandomUrl.orNull?.let { add("--lobby-map-random-url=$it") }
    }

    private fun RuntimeCommand.commandName() = when (this) {
        is RuntimeCommand.ServeProxy -> "runProxy"
        is RuntimeCommand.ServeFull -> "runNetwork"
        is RuntimeCommand.ServeBackend -> "runBackend"
        is RuntimeCommand.RegisterExternal -> "registerBackend"
        is RuntimeCommand.UnregisterExternal -> "unregisterBackend"
        is RuntimeCommand.StopNetwork -> "stopNetwork"
        is RuntimeCommand.ReloadNetwork -> "reloadNetwork"
        is RuntimeCommand.RestartBackend -> "restartBackend"
        is RuntimeCommand.NetworkStatus -> "networkStatus"
    }

}

internal fun extension(project: Project): DevelopmentNetworkExtension = project.extensions.getByType(DevelopmentNetworkExtension::class.java)
internal fun baseDir(project: Project): File = extension(project).networkBase.get().asFile

internal fun backendName(project: Project): String = extension(project).networkBackend.get().also {
    check(it.matches(Regex("[A-Za-z0-9_-]+"))) { "networkBackend '$it' invalid (use [A-Za-z0-9_-]+)" }
}

internal fun owner(project: Project, name: String): String = extension(project).networkRegistrationOwner.orNull
    ?.trim()?.takeIf(String::isNotEmpty)
    ?: "gradle-${("${project.path}:$name").replace(Regex("[^A-Za-z0-9_.:/-]"), "_")}"

internal fun managedOwner(project: Project, name: String): String = owner(project, name)

internal fun configuredJar(project: Project): File {
    val taskName = extension(project).networkJarTask.get()
    val jar = project.tasks.named(taskName).get() as? Jar
        ?: throw GradleException("task '$taskName' is not a Jar task — set networkJarTask to a Jar task")
    val file = jar.archiveFile.get().asFile
    check(file.isFile) { "missing $file — run $taskName first" }
    return file
}

internal fun users(extension: DevelopmentNetworkExtension): List<String> = extension.networkDevUsers.get().split(Regex("[,\\s]+")).map(String::trim).filter(String::isNotEmpty).also {
    require(it.isNotEmpty()) { "networkDevUsers must contain at least one user" }
}
internal fun duration(seconds: Long, property: String): Duration = Duration.ofSeconds(seconds).also { require(seconds > 0) { "$property must be positive" } }
internal fun port(value: Int, property: String, allowZero: Boolean = false): Int {
    val valid = if (allowZero) value == 0 || value in 1024..65535 else value in 1024..65535
    require(valid) { "$property '$value' invalid (use ${if (allowZero) "0 or " else ""}1024..65535)" }
    return value
}
