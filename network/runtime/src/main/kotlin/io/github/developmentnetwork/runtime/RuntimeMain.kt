package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.controller.InfrastructureController
import io.github.developmentnetwork.runtime.controller.InfrastructureMode
import io.github.developmentnetwork.runtime.controller.InfrastructureRequest
import io.github.developmentnetwork.runtime.controller.ManagedBackendController
import io.github.developmentnetwork.runtime.controller.ManagedBackendRequest
import io.github.developmentnetwork.runtime.service.NetworkStatusRequest
import io.github.developmentnetwork.runtime.service.RegisterExternalRequest
import io.github.developmentnetwork.runtime.service.ReloadNetworkRequest
import io.github.developmentnetwork.runtime.service.ReloadService
import io.github.developmentnetwork.runtime.service.RestartBackendRequest
import io.github.developmentnetwork.runtime.service.RestartService
import io.github.developmentnetwork.runtime.service.RegistrationService
import io.github.developmentnetwork.runtime.service.StatusService
import io.github.developmentnetwork.runtime.service.StopNetworkRequest
import io.github.developmentnetwork.runtime.service.StopService
import io.github.developmentnetwork.runtime.service.UnregisterExternalRequest
import io.github.developmentnetwork.runtime.service.UnregistrationService
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

/** The command envelope passed from a Gradle adapter to the embedded runtime. */
data class RuntimeRequest(val command: String, val settings: Map<String, String>)

/** Typed commands prevent lifecycle operations from silently sharing settings. */
sealed interface RuntimeCommand {
    data class ServeProxy(val request: InfrastructureRequest) : RuntimeCommand
    data class ServeFull(val request: InfrastructureRequest) : RuntimeCommand
    data class ServeBackend(val request: ManagedBackendRequest) : RuntimeCommand
    data class RegisterExternal(val request: RegisterExternalRequest) : RuntimeCommand
    data class UnregisterExternal(val request: UnregisterExternalRequest) : RuntimeCommand
    data class StopNetwork(val request: StopNetworkRequest) : RuntimeCommand
    data class ReloadNetwork(val request: ReloadNetworkRequest) : RuntimeCommand
    data class RestartBackend(val request: RestartBackendRequest) : RuntimeCommand
    data class NetworkStatus(val request: NetworkStatusRequest) : RuntimeCommand
}

private val supportedCommands = setOf(
    "runProxy", "registerBackend", "unregisterBackend", "runBackend", "runNetwork",
    "stopNetwork", "reloadNetwork", "restartBackend", "networkStatus",
    "serve-proxy", "serve-full", "serve-backend", "register-external", "unregister-external",
    "stop-network", "reload-network", "restart-backend", "network-status",
)

/** Parse one command token followed by deterministic --key=value settings. */
fun parseRuntimeRequest(arguments: List<String>): RuntimeRequest {
    require(arguments.isNotEmpty()) { "Missing runtime command" }
    val command = arguments.first()
    require(command.isNotEmpty() && !command.startsWith("--")) { "Missing runtime command" }
    require(command in supportedCommands) { "Unknown command: $command" }
    val settings = LinkedHashMap<String, String>()
    arguments.drop(1).forEach { argument ->
        require(argument.startsWith("--")) { "Expected a --key=value setting after command $command, got: $argument" }
        val separator = argument.indexOf('=', startIndex = 2)
        require(separator > 2) { "Expected a --key=value setting, got: $argument" }
        val key = argument.substring(2, separator)
        require(key.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid runtime setting name: $key" }
        require(key !in settings) { "Duplicate runtime setting: $key" }
        val value = argument.substring(separator + 1)
        require('\n' !in value && '\r' !in value) { "Runtime setting $key must be a single line" }
        settings[key] = value
    }
    return RuntimeRequest(command, settings.toMap())
}

/** Convert a parsed envelope into a typed request, rejecting absent/malformed values. */
fun parseRuntimeCommand(arguments: List<String>): RuntimeCommand = parseRuntimeCommand(parseRuntimeRequest(arguments))

fun parseRuntimeCommand(request: RuntimeRequest): RuntimeCommand {
    val command = request.command
    val s = request.settings
    val base = Path.of(required(s, "base"))
    val target = s["target-server"] ?: "localhost"
    val proxyPort = int(s, "proxy-port", 25565)
    val lobbyPort = int(s, "lobby-port", 30066)
    val timeout = durationSeconds(s, "timeout", 240)
    val shutdownTimeout = durationSeconds(s, "shutdown-timeout", 30)
    val onlineMode = bool(s, "online-mode", true)
    val owner = s["owner"] ?: s["registration-owner"] ?: "runtime-${ProcessHandle.current().pid()}"
    val name = s["name"] ?: s["backend"] ?: s["network-backend"]
    return when (command) {
        "runProxy", "serve-proxy" -> RuntimeCommand.ServeProxy(InfrastructureRequest(proxyPort = proxyPort, lobbyPort = lobbyPort, targetServer = target, onlineMode = onlineMode, owner = owner, readinessTimeout = timeout, shutdownTimeout = shutdownTimeout))
        "runNetwork", "serve-full" -> RuntimeCommand.ServeFull(InfrastructureRequest(proxyPort = proxyPort, lobbyPort = lobbyPort, targetServer = target, onlineMode = onlineMode, owner = owner, backendName = requiredValue(name, "name"), backendPort = optionalInt(s, "backend-port"), backendOwner = owner, backendWorkDir = s["backend-dir"]?.let(Path::of), readinessTimeout = timeout, shutdownTimeout = shutdownTimeout))
        "runBackend", "serve-backend" -> RuntimeCommand.ServeBackend(ManagedBackendRequest(requiredValue(name, "name"), owner, int(s, "backend-port", requiredInt(s, "port")), Path.of(s["backend-dir"] ?: base.resolve("runtime/auto/${requiredValue(name, "name")}").toString()), readinessTimeout = timeout, shutdownTimeout = shutdownTimeout))
        "registerBackend", "register-external" -> RuntimeCommand.RegisterExternal(RegisterExternalRequest(requiredValue(name, "name"), requiredInt(s, "port"), requiredValue(s["registration-owner"] ?: s["owner"], "owner"), Path.of(required(s, "server-dir")), targetServer = target, readinessTimeout = timeout, controlTimeout = shutdownTimeout))
        "unregisterBackend", "unregister-external" -> RuntimeCommand.UnregisterExternal(UnregisterExternalRequest(requiredValue(name, "name"), requiredValue(s["registration-owner"] ?: s["owner"], "owner"), targetServer = target, controlTimeout = shutdownTimeout))
        "stopNetwork", "stop-network" -> RuntimeCommand.StopNetwork(StopNetworkRequest(s["owner"], controlTimeout = shutdownTimeout, shutdownTimeout = timeout))
        "reloadNetwork", "reload-network" -> RuntimeCommand.ReloadNetwork(ReloadNetworkRequest(target, proxyPort, onlineMode, shutdownTimeout))
        "restartBackend", "restart-backend" -> RuntimeCommand.RestartBackend(RestartBackendRequest(requiredValue(name, "name"), owner, optionalInt(s, "backend-port"), s["backend-dir"]?.let(Path::of), readinessTimeout = timeout, shutdownTimeout = shutdownTimeout))
        "networkStatus", "network-status" -> RuntimeCommand.NetworkStatus(NetworkStatusRequest(target, optionalInt(s, "proxy-port"), lobbyPort, timeout))
        else -> error("Unknown command: $command")
    }
}

/** Execute a typed request and return its stable operation exit code. */
fun runRuntime(arguments: List<String>): Int = try {
    val parsed = parseRuntimeCommand(arguments)
    when (parsed) {
        is RuntimeCommand.ServeProxy -> InfrastructureController(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).run(InfrastructureMode.PROXY, parsed.request)
        is RuntimeCommand.ServeFull -> InfrastructureController(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).run(InfrastructureMode.FULL, parsed.request)
        is RuntimeCommand.ServeBackend -> ManagedBackendController(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).run(parsed.request)
        is RuntimeCommand.RegisterExternal -> RegistrationService(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).execute(parsed.request)
        is RuntimeCommand.UnregisterExternal -> UnregistrationService(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).execute(parsed.request)
        is RuntimeCommand.StopNetwork -> StopService(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).execute(parsed.request)
        is RuntimeCommand.ReloadNetwork -> ReloadService(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).execute(parsed.request)
        is RuntimeCommand.RestartBackend -> RestartService(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).execute(parsed.request)
        is RuntimeCommand.NetworkStatus -> StatusService(RuntimeLayout(Path.of(required(parseRuntimeRequest(arguments).settings, "base")))).execute(parsed.request)
    }
} catch (error: IllegalArgumentException) {
    System.err.println("Runtime request error: ${error.message ?: "invalid request"}")
    2
} catch (error: Exception) {
    System.err.println("Runtime operation error: ${error.message ?: error::class.simpleName}")
    1
}

fun main(arguments: Array<String>) { exitProcess(runRuntime(arguments.toList())) }

private fun required(settings: Map<String, String>, key: String): String = requiredValue(settings[key], key)
private fun requiredValue(value: String?, key: String): String = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing required --$key setting")
private fun requiredInt(settings: Map<String, String>, key: String): Int = int(settings, key, null)
private fun int(settings: Map<String, String>, key: String, default: Int?): Int = settings[key]?.toIntOrNull()?.also { require(it in 1024..65535) { "Invalid --$key value: ${settings[key]}" } } ?: default ?: throw IllegalArgumentException("Missing or invalid --$key setting")
private fun optionalInt(settings: Map<String, String>, key: String): Int? = settings[key]?.toIntOrNull()?.also { require(it in 1024..65535) { "Invalid --$key value: ${settings[key]}" } }
private fun bool(settings: Map<String, String>, key: String, default: Boolean): Boolean = settings[key]?.let { when (it) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("Invalid --$key value: $it") } } ?: default
private fun durationSeconds(settings: Map<String, String>, key: String, default: Long): Duration = Duration.ofSeconds(settings[key]?.toLongOrNull()?.also { require(it > 0) { "Invalid --$key value: ${settings[key]}" } } ?: default)
