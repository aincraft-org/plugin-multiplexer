package io.github.developmentnetwork

import io.github.developmentnetwork.runtime.RuntimeCommand
import io.github.developmentnetwork.runtime.controller.InfrastructureRequest
import io.github.developmentnetwork.runtime.controller.ManagedBackendRequest
import io.github.developmentnetwork.runtime.service.NetworkStatusRequest
import io.github.developmentnetwork.runtime.service.RegisterExternalRequest
import io.github.developmentnetwork.runtime.service.ReloadNetworkRequest
import io.github.developmentnetwork.runtime.service.RestartBackendRequest
import io.github.developmentnetwork.runtime.service.StopNetworkRequest
import io.github.developmentnetwork.runtime.service.UnregisterExternalRequest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

class DevNetworkPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("developmentNetwork", DevelopmentNetworkExtension::class.java)
        extension.networkBase.convention(project.providers.gradleProperty("networkBase").map { project.layout.projectDirectory.dir(it) }.orElse(project.layout.projectDirectory.dir("run/network")))
        extension.networkBackend.convention(project.providers.gradleProperty("networkBackend").orElse(project.name))
        extension.networkBackendPort.convention(project.providers.gradleProperty("networkBackendPort").map { raw -> raw.toIntOrNull() ?: throw IllegalArgumentException("networkBackendPort '$raw' invalid (use 1024..65535)") })
        extension.networkProxyPort.convention(project.providers.gradleProperty("networkProxyPort").map { raw -> raw.toIntOrNull() ?: throw IllegalArgumentException("networkProxyPort '$raw' invalid (use 0 or 1024..65535)") }.orElse(25565))
        extension.networkJarTask.convention(project.providers.gradleProperty("networkJarTask").orElse("jar"))
        extension.networkDevUsers.convention(project.providers.gradleProperty("networkDevUsers").orElse(project.providers.environmentVariable("DEV_NETWORK_DEV_USERS")).orElse("dev"))
        extension.networkOnlineMode.convention(project.providers.gradleProperty("networkOnlineMode").map {
            when (it) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("networkOnlineMode '$it' invalid (use true or false)") }
        }.orElse(false))
        extension.networkRegistrationOwner.convention(project.providers.gradleProperty("networkRegistrationOwner"))
        extension.networkTargetServer.convention(project.providers.gradleProperty("networkTargetServer").orElse("localhost"))
        extension.networkLobbyPort.convention(project.providers.gradleProperty("networkLobbyPort").map { raw -> raw.toIntOrNull() ?: throw IllegalArgumentException("networkLobbyPort '$raw' invalid") }.orElse(30066))
        extension.networkLobbyMapUrl.convention(project.providers.gradleProperty("networkLobbyMapUrl"))
        extension.networkLobbyMapSha256.convention(project.providers.gradleProperty("networkLobbyMapSha256"))
        extension.networkLobbyMapRandomUrl.convention(project.providers.gradleProperty("networkLobbyMapRandomUrl"))
        extension.networkTimeout.convention(project.providers.gradleProperty("networkTimeout").map { raw -> raw.toLongOrNull() ?: throw IllegalArgumentException("networkTimeout '$raw' invalid") }.orElse(240))
        extension.networkShutdownTimeout.convention(project.providers.gradleProperty("networkShutdownTimeout").map { raw -> raw.toLongOrNull() ?: throw IllegalArgumentException("networkShutdownTimeout '$raw' invalid") }.orElse(30))
        extension.networkControlTimeout.convention(project.providers.gradleProperty("networkControlTimeout").map { raw -> raw.toLongOrNull() ?: throw IllegalArgumentException("networkControlTimeout '$raw' invalid") }.orElse(5))
        extension.networkServerDir.convention(project.providers.gradleProperty("networkServerDir"))

        register(project, "runProxy", RunProxyTask::class.java, "Own and run the shared Velocity proxy plus lobby")
        register(project, "registerBackend", RegisterBackendTask::class.java, "Attach an already-running backend (never starts or stops Paper)")
        register(project, "unregisterBackend", UnregisterBackendTask::class.java, "Remove this project's external backend registration")
        registerManaged(project, "runBackend", RunBackendTask::class.java, "Build, register, and run this project's managed Paper backend")
        registerManaged(project, "runNetwork", RunNetworkTask::class.java, "Run a one-project full network (proxy + lobby + backend)")
        register(project, "stopNetwork", StopNetworkTask::class.java, "Stop the owned network controller and its components")
        register(project, "reloadNetwork", ReloadNetworkTask::class.java, "Regenerate network configuration and reload the proxy")
        registerManaged(project, "restartBackend", RestartBackendTask::class.java, "Restart this project's managed Paper backend")
        register(project, "networkStatus", NetworkStatusTask::class.java, "Probe network endpoints and report their status")
    }

    private fun <T : org.gradle.api.Task> register(project: Project, name: String, type: Class<T>, description: String) = project.tasks.register(name, type) { it.group = "network"; it.description = description }
    private fun <T : org.gradle.api.Task> registerManaged(project: Project, name: String, type: Class<T>, description: String) = register(project, name, type, description).also { task -> task.configure { it.dependsOn(extension(project).networkJarTask.map { jar -> project.tasks.named(jar) }) } }
}

@DisableCachingByDefault(because = "Network tasks start, stop, or mutate external services")
abstract class RunProxyTask : org.gradle.api.DefaultTask() {
    @TaskAction fun runProxy() { val e = extension(project); NetworkTaskSupport.run(project, RuntimeCommand.ServeProxy(infrastructure(owner(project, project.name), e)), true) }
}

@DisableCachingByDefault(because = "Network tasks mutate shared network registration state")
abstract class RegisterBackendTask : org.gradle.api.DefaultTask() {
    @TaskAction fun registerBackend() {
        val e = extension(project); val name = backendName(project)
        val backendPort = e.networkBackendPort.orNull ?: throw org.gradle.api.GradleException("registerBackend requires -PnetworkBackendPort=<port>; it attaches an already-running external Paper server")
        port(backendPort, "networkBackendPort")
        val serverDir = e.networkServerDir.orNull?.let(project::file) ?: baseDir(project).resolve("runtime/external/$name")
        NetworkTaskSupport.run(
            project,
            RuntimeCommand.RegisterExternal(
                RegisterExternalRequest(
                    name = name,
                    port = backendPort,
                    owner = owner(project, name),
                    serverDir = serverDir.toPath(),
                    targetServer = e.networkTargetServer.get(),
                    controlTimeout = duration(e.networkControlTimeout.get(), "networkControlTimeout"),
                    readinessTimeout = duration(e.networkTimeout.get(), "networkTimeout"),
                    lobbyPort = port(e.networkLobbyPort.get(), "networkLobbyPort"),
                ),
            ),
            false,
        )
    }
}
@DisableCachingByDefault(because = "Network tasks mutate shared network registration state")
abstract class UnregisterBackendTask : org.gradle.api.DefaultTask() {
    @TaskAction fun unregisterBackend() {
        val e = extension(project)
        val name = backendName(project)
        NetworkTaskSupport.run(
            project,
            RuntimeCommand.UnregisterExternal(
                UnregisterExternalRequest(
                    name = name,
                    owner = owner(project, name),
                    targetServer = e.networkTargetServer.get(),
                    controlTimeout = duration(e.networkControlTimeout.get(), "networkControlTimeout"),
                    lobbyPort = port(e.networkLobbyPort.get(), "networkLobbyPort"),
                ),
            ),
            false,
        )
    }
}

@DisableCachingByDefault(because = "Network tasks start and own an external Paper process")
abstract class RunBackendTask : org.gradle.api.DefaultTask() {
    @TaskAction fun runBackend() {
        val e = extension(project); val name = backendName(project); val jar = configuredJar(project); val workDir = baseDir(project).resolve("runtime/auto/$name")
        val requestedPort = e.networkBackendPort.orNull?.let { port(it, "networkBackendPort") }
        NetworkTaskSupport.run(project, RuntimeCommand.ServeBackend(ManagedBackendRequest(name, managedOwner(project, name), requestedPort, workDir.toPath(), pluginJar = jar.toPath(), readinessTimeout = duration(e.networkTimeout.get(), "networkTimeout"), shutdownTimeout = duration(e.networkShutdownTimeout.get(), "networkShutdownTimeout"), devUsers = users(e), proxyPort = port(e.networkProxyPort.get(), "networkProxyPort", true), lobbyPort = port(e.networkLobbyPort.get(), "networkLobbyPort"), proxyOwner = owner(project, project.name))), true)
    }
}


@DisableCachingByDefault(because = "Network tasks start and own external services")
abstract class RunNetworkTask : org.gradle.api.DefaultTask() {
    @TaskAction fun runNetwork() {
        val e = extension(project); val name = backendName(project); val jar = configuredJar(project); val workDir = baseDir(project).resolve("runtime/auto/$name")
        val requestedPort = e.networkBackendPort.orNull?.let { port(it, "networkBackendPort") }
        val runOwner = managedOwner(project, name)
        NetworkTaskSupport.run(project, RuntimeCommand.ServeFull(infrastructure(runOwner, e).copy(backendName = name, backendPort = requestedPort, backendOwner = runOwner, backendWorkDir = workDir.toPath(), backendPluginJar = jar.toPath())), true)
    }
}

@DisableCachingByDefault(because = "Network tasks stop external services")
abstract class StopNetworkTask : org.gradle.api.DefaultTask() {
    @TaskAction fun stopNetwork() {
        val e = extension(project)
        NetworkTaskSupport.run(
            project,
            RuntimeCommand.StopNetwork(
                StopNetworkRequest(
                    owner(project, project.name),
                    duration(e.networkControlTimeout.get(), "networkControlTimeout"),
                    duration(e.networkShutdownTimeout.get(), "networkShutdownTimeout"),
                ),
            ),
            false,
        )
    }
}

@DisableCachingByDefault(because = "Network tasks mutate proxy configuration")
abstract class ReloadNetworkTask : org.gradle.api.DefaultTask() {
    @TaskAction fun reloadNetwork() {
        val e = extension(project)
        NetworkTaskSupport.run(
            project,
            RuntimeCommand.ReloadNetwork(
                ReloadNetworkRequest(
                    targetServer = e.networkTargetServer.get(),
                    proxyPort = port(e.networkProxyPort.get(), "networkProxyPort", true),
                    onlineMode = e.networkOnlineMode.get(),
                    controlTimeout = duration(e.networkControlTimeout.get(), "networkControlTimeout"),
                    lobbyPort = port(e.networkLobbyPort.get(), "networkLobbyPort"),
                ),
            ),
            false,
        )
    }
}

@DisableCachingByDefault(because = "Network tasks restart an external Paper process")
abstract class RestartBackendTask : org.gradle.api.DefaultTask() {
    @TaskAction fun restartBackend() { val e = extension(project); val name = backendName(project); val requestedPort = e.networkBackendPort.orNull?.let { port(it, "networkBackendPort") }; NetworkTaskSupport.run(project, RuntimeCommand.RestartBackend(RestartBackendRequest(name, managedOwner(project, name), requestedPort, baseDir(project).resolve("runtime/auto/$name").toPath(), pluginJar = configuredJar(project).toPath(), readinessTimeout = duration(e.networkTimeout.get(), "networkTimeout"), shutdownTimeout = duration(e.networkShutdownTimeout.get(), "networkShutdownTimeout"))), false) }
}

@DisableCachingByDefault(because = "Network status probes external services")
abstract class NetworkStatusTask : org.gradle.api.DefaultTask() {
    @TaskAction fun networkStatus() { val e = extension(project); NetworkTaskSupport.run(project, RuntimeCommand.NetworkStatus(NetworkStatusRequest(e.networkTargetServer.get(), port(e.networkProxyPort.get(), "networkProxyPort", true).takeIf { it != 0 }, port(e.networkLobbyPort.get(), "networkLobbyPort"), duration(e.networkTimeout.get(), "networkTimeout"))), false) }
}

private fun infrastructure(owner: String, e: DevelopmentNetworkExtension) = InfrastructureRequest(
    proxyPort = port(e.networkProxyPort.get(), "networkProxyPort", true), lobbyPort = port(e.networkLobbyPort.get(), "networkLobbyPort"), targetServer = e.networkTargetServer.get(), onlineMode = e.networkOnlineMode.get(), owner = owner,
    readinessTimeout = duration(e.networkTimeout.get(), "networkTimeout"), shutdownTimeout = duration(e.networkShutdownTimeout.get(), "networkShutdownTimeout"), devUsers = users(e),
)
