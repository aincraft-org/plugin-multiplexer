package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.artifact.LobbyMapOptions
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
import io.github.developmentnetwork.runtime.model.BackendName
import java.net.URI
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

private val settingName = Regex("[A-Za-z][A-Za-z0-9_.-]*")
private val checksum = Regex("[0-9a-f]{64}")

/** Parse one command token followed by deterministic --key=value settings. */
fun parseRuntimeRequest(arguments: List<String>): RuntimeRequest {
    require(arguments.isNotEmpty()) { "Missing runtime command" }
    val command = arguments.first()
    require(command.isNotEmpty() && !command.startsWith("--")) { "Missing runtime command" }
    require(command in supportedCommands) { "Unknown command: $command" }
    val settings = LinkedHashMap<String, String>()
    arguments.drop(1).forEach { argument ->
        require(argument.startsWith("--")) {
            "Expected a --key=value setting after command $command, got: $argument"
        }
        val separator = argument.indexOf('=', startIndex = 2)
        require(separator > 2) { "Expected a --key=value setting, got: $argument" }
        val key = argument.substring(2, separator)
        require(settingName.matches(key)) { "Invalid runtime setting name: $key" }
        require(key !in settings) { "Duplicate runtime setting: $key" }
        val value = argument.substring(separator + 1)
        require('\n' !in value && '\r' !in value) { "Runtime setting $key must be a single line" }
        settings[key] = value
    }
    return RuntimeRequest(command, settings.toMap())
}

/** Convert a parsed envelope into a typed request, rejecting absent/malformed values. */
fun parseRuntimeCommand(arguments: List<String>): RuntimeCommand =
    parseRuntimeCommand(parseRuntimeRequest(arguments))

fun parseRuntimeCommand(request: RuntimeRequest): RuntimeCommand {
    val command = request.command
    require(command in supportedCommands) { "Unknown command: $command" }
    val settings = request.settings
    settings.keys.forEach { require(settingName.matches(it)) { "Invalid runtime setting name: $it" } }
    val base = Path.of(required(settings, "base"))
    val target = oneSetting(settings, "target-server", "host") ?: "localhost"
    val proxyPort = port(settings, "proxy-port", default = 25565, allowZero = true)
    val lobbyPort = port(settings, "lobby-port", default = 30066)
    val readinessTimeout = durationSeconds(settings, "timeout", 240)
    val shutdownTimeout = durationSeconds(settings, "shutdown-timeout", 30)
    val controlTimeout = durationSeconds(settings, "control-timeout", 5)
    val onlineMode = bool(settings, "online-mode", false)
    val owner = optionalOwner(settings) ?: "runtime-${ProcessHandle.current().pid()}"
    val name = oneSetting(settings, "name", "backend", "network-backend")
    val mapOptions = parseMapOptions(settings)

    when (command) {
        "runProxy", "serve-proxy" -> {
            requireKeys(command, settings, PROXY_KEYS)
            return RuntimeCommand.ServeProxy(
                InfrastructureRequest(
                    proxyPort = proxyPort,
                    lobbyPort = lobbyPort,
                    targetServer = target,
                    onlineMode = onlineMode,
                    owner = owner,
                    readinessTimeout = readinessTimeout,
                    shutdownTimeout = shutdownTimeout,
                    mapOptions = mapOptions,
                    devUsers = users(settings),
                ),
            )
        }
        "runNetwork", "serve-full" -> {
            requireKeys(command, settings, FULL_KEYS)
            val backendName = validatedName(name)
            return RuntimeCommand.ServeFull(
                InfrastructureRequest(
                    proxyPort = proxyPort,
                    lobbyPort = lobbyPort,
                    targetServer = target,
                    onlineMode = onlineMode,
                    owner = owner,
                    backendName = backendName.value,
                    backendPort = optionalPort(settings, "backend-port", "port"),
                    backendOwner = owner,
                    backendWorkDir = settings["backend-dir"]?.let(Path::of),
                    backendPluginJar = settings["plugin-jar"]?.let(Path::of),
                    readinessTimeout = readinessTimeout,
                    shutdownTimeout = shutdownTimeout,
                    mapOptions = mapOptions,
                    devUsers = users(settings),
                ),
            )
        }
        "runBackend", "serve-backend" -> {
            requireKeys(command, settings, BACKEND_KEYS)
            val backendName = validatedName(name)
            val backendPort = optionalPort(settings, "backend-port", "port")
            val workDir = settings["backend-dir"]?.let(Path::of)
                ?: base.resolve("runtime/auto/${backendName.value}")
            val proxyOwner = settings["proxy-owner"]?.also(::validateOwner)
            return RuntimeCommand.ServeBackend(
                ManagedBackendRequest(
                    name = backendName.value,
                    owner = owner,
                    port = backendPort,
                    workDir = workDir,
                    pluginJar = settings["plugin-jar"]?.let(Path::of),
                    readinessTimeout = readinessTimeout,
                    shutdownTimeout = shutdownTimeout,
                    devUsers = users(settings),
                    proxyPort = proxyPort,
                    lobbyPort = lobbyPort,
                    proxyOwner = proxyOwner,
                ),
            )
        }
        "registerBackend", "register-external" -> {
            requireKeys(command, settings, EXTERNAL_REGISTER_KEYS)
            val backendName = validatedName(name)
            val registrationOwner = requiredOwner(settings)
            return RuntimeCommand.RegisterExternal(
                RegisterExternalRequest(
                    name = backendName.value,
                    port = requiredPort(settings, "port"),
                    owner = registrationOwner,
                    serverDir = Path.of(required(settings, "server-dir")),
                    host = settings["host"] ?: "localhost",
                    targetServer = target,
                    readinessTimeout = readinessTimeout,
                    controlTimeout = controlTimeout,
                    lobbyPort = lobbyPort,
                ),
            )
        }
        "unregisterBackend", "unregister-external" -> {
            requireKeys(command, settings, EXTERNAL_UNREGISTER_KEYS)
            val backendName = validatedName(name)
            return RuntimeCommand.UnregisterExternal(
                UnregisterExternalRequest(
                    name = backendName.value,
                    owner = requiredOwner(settings),
                    targetServer = target,
                    controlTimeout = controlTimeout,
                    lobbyPort = lobbyPort,
                ),
            )
        }
        "stopNetwork", "stop-network" -> {
            requireKeys(command, settings, STOP_KEYS)
            return RuntimeCommand.StopNetwork(
                StopNetworkRequest(
                    owner = settings["owner"],
                    controlTimeout = controlTimeout,
                    shutdownTimeout = shutdownTimeout,
                    managedOnly = bool(settings, "managed-only", false),
                ),
            )
        }
        "reloadNetwork", "reload-network" -> {
            requireKeys(command, settings, RELOAD_KEYS)
            return RuntimeCommand.ReloadNetwork(
                ReloadNetworkRequest(
                    targetServer = target,
                    proxyPort = proxyPort,
                    lobbyPort = lobbyPort,
                    onlineMode = onlineMode,
                    controlTimeout = controlTimeout,
                ),
            )
        }
        "restartBackend", "restart-backend" -> {
            requireKeys(command, settings, RESTART_KEYS)
            val backendName = validatedName(name)
            return RuntimeCommand.RestartBackend(
                RestartBackendRequest(
                    name = backendName.value,
                    owner = requiredOwner(settings),
                    port = optionalPort(settings, "backend-port", "port"),
                    workDir = settings["backend-dir"]?.let(Path::of),
                    pluginJar = settings["plugin-jar"]?.let(Path::of),
                    readinessTimeout = readinessTimeout,
                    shutdownTimeout = shutdownTimeout,
                ),
            )
        }
        "networkStatus", "network-status" -> {
            requireKeys(command, settings, STATUS_KEYS)
            return RuntimeCommand.NetworkStatus(
                NetworkStatusRequest(
                    host = target,
                    proxyPort = settings["proxy-port"]?.let { port(settings, "proxy-port", null, allowZero = false) },
                    lobbyPort = lobbyPort,
                    timeout = readinessTimeout,
                ),
            )
        }
        else -> error("Unknown command: $command")
    }
}

/** Execute a typed request and return its stable operation exit code. */
fun runRuntime(arguments: List<String>): Int {
    val parsed = try {
        val envelope = parseRuntimeRequest(arguments)
        parseRuntimeCommand(envelope) to RuntimeLayout(Path.of(required(envelope.settings, "base")))
    } catch (error: IllegalArgumentException) {
        System.err.println("Runtime request error: ${error.message ?: "invalid request"}")
        return 2
    }
    val command = parsed.first
    val layout = parsed.second
    return try {
        when (command) {
            is RuntimeCommand.ServeProxy -> InfrastructureController(layout).run(InfrastructureMode.PROXY, command.request)
            is RuntimeCommand.ServeFull -> InfrastructureController(layout).run(InfrastructureMode.FULL, command.request)
            is RuntimeCommand.ServeBackend -> ManagedBackendController(layout).run(command.request)
            is RuntimeCommand.RegisterExternal -> RegistrationService(layout).execute(command.request)
            is RuntimeCommand.UnregisterExternal -> UnregistrationService(layout).execute(command.request)
            is RuntimeCommand.StopNetwork -> StopService(layout).execute(command.request)
            is RuntimeCommand.ReloadNetwork -> ReloadService(layout).execute(command.request)
            is RuntimeCommand.RestartBackend -> RestartService(layout).execute(command.request)
            is RuntimeCommand.NetworkStatus -> StatusService(layout).execute(command.request)
        }
    } catch (error: Exception) {
        System.err.println("Runtime operation error: ${error.message ?: error::class.simpleName}")
        1
    }
}

fun main(arguments: Array<String>) { exitProcess(runRuntime(arguments.toList())) }

private val PROXY_KEYS = setOf(
    "base", "target-server", "host", "proxy-port", "lobby-port", "online-mode", "owner",
    "registration-owner", "timeout", "shutdown-timeout", "lobby-map-url", "lobby-map-sha256",
    "lobby-map-random-url", "dev-users",
)
private val FULL_KEYS = PROXY_KEYS + setOf("name", "backend", "network-backend", "backend-port", "port", "backend-dir", "plugin-jar")
private val BACKEND_KEYS = setOf(
    "base", "name", "backend", "network-backend", "backend-port", "port", "backend-dir", "plugin-jar", "owner",
    "registration-owner", "proxy-owner", "proxy-port", "lobby-port", "timeout", "shutdown-timeout", "dev-users",
)
private val EXTERNAL_REGISTER_KEYS = setOf(
    "base", "name", "backend", "network-backend", "port", "registration-owner", "owner", "server-dir",
    "target-server", "host", "lobby-port", "timeout", "control-timeout",
)
private val EXTERNAL_UNREGISTER_KEYS = setOf(
    "base", "name", "backend", "network-backend", "registration-owner", "owner", "target-server", "host",
    "lobby-port", "control-timeout",
)
private val STOP_KEYS = setOf("base", "owner", "managed-only", "control-timeout", "shutdown-timeout")
private val RELOAD_KEYS = setOf("base", "target-server", "host", "proxy-port", "lobby-port", "online-mode", "control-timeout")
private val RESTART_KEYS = setOf(
    "base", "name", "backend", "network-backend", "owner", "registration-owner", "backend-port", "port",
    "backend-dir", "plugin-jar", "timeout", "shutdown-timeout",
)
private val STATUS_KEYS = setOf("base", "target-server", "host", "proxy-port", "lobby-port", "timeout")

private fun requireKeys(command: String, settings: Map<String, String>, allowed: Set<String>) {
    val unknown = settings.keys - allowed
    require(unknown.isEmpty()) { "Setting(s) ${unknown.sorted().joinToString()} are not valid for $command" }
}

private fun required(settings: Map<String, String>, key: String): String =
    settings[key]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing required --$key setting")

private fun oneSetting(settings: Map<String, String>, vararg keys: String): String? {
    val present = keys.filter { settings.containsKey(it) }
    require(present.size <= 1) { "Incompatible settings: ${present.joinToString(" and ")}" }
    return present.firstOrNull()?.let { required(settings, it) }
}

private fun optionalOwner(settings: Map<String, String>): String? =
    oneSetting(settings, "owner", "registration-owner")?.also { validateOwner(it) }

private fun requiredOwner(settings: Map<String, String>): String =
    optionalOwner(settings) ?: throw IllegalArgumentException("Missing required --registration-owner setting")

private fun validateOwner(owner: String) {
    require(owner.isNotBlank() && '\n' !in owner && '\r' !in owner) { "Owner must be a non-blank single line" }
}

private fun validatedName(raw: String?): BackendName =
    BackendName(raw?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing required --name setting"))

private fun port(settings: Map<String, String>, key: String, default: Int?, allowZero: Boolean = false): Int {
    val raw = settings[key] ?: return default ?: throw IllegalArgumentException("Missing required --$key setting")
    val parsed = raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid --$key value: $raw")
    val minimum = if (allowZero) 0 else 1024
    require(parsed in minimum..65535) { "Invalid --$key value: $raw" }
    return parsed
}

private fun optionalPort(settings: Map<String, String>, vararg keys: String): Int? =
    oneSetting(settings, *keys)?.let { raw ->
        val parsed = raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid backend port value: $raw")
        require(parsed in 1024..65535) { "Invalid backend port value: $raw" }
        parsed
    }

private fun requiredPort(settings: Map<String, String>, vararg keys: String): Int =
    optionalPort(settings, *keys) ?: throw IllegalArgumentException("Missing required --${keys.first()} setting")

private fun bool(settings: Map<String, String>, key: String, default: Boolean): Boolean =
    settings[key]?.let { when (it) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Invalid --$key value: $it")
    } } ?: default

private fun durationSeconds(settings: Map<String, String>, key: String, default: Long): Duration {
    val raw = settings[key] ?: return Duration.ofSeconds(default)
    val seconds = raw.toLongOrNull() ?: throw IllegalArgumentException("Invalid --$key value: $raw")
    require(seconds > 0) { "Invalid --$key value: $raw" }
    return Duration.ofSeconds(seconds)
}

private fun users(settings: Map<String, String>): List<String> =
    settings["dev-users"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.also {
        require(it.isNotEmpty()) { "--dev-users must contain at least one user" }
    } ?: listOf("dev")

private fun parseMapOptions(settings: Map<String, String>): LobbyMapOptions {
    val staticUrl = settings["lobby-map-url"]?.let { uriSetting("lobby-map-url", it) }
    val staticSha = settings["lobby-map-sha256"]
    val randomUrl = settings["lobby-map-random-url"]?.let { uriSetting("lobby-map-random-url", it) }
    require(staticUrl == null == (staticSha == null)) {
        "Static lobby map mode requires both --lobby-map-url and --lobby-map-sha256"
    }
    if (staticSha != null) require(checksum.matches(staticSha)) {
        "Invalid --lobby-map-sha256 value; expected exactly 64 lowercase hexadecimal characters"
    }
    require(randomUrl == null || staticUrl == null) {
        "--lobby-map-random-url is incompatible with static lobby map settings"
    }
    return LobbyMapOptions(staticUrl = staticUrl, staticSha256 = staticSha, randomUrl = randomUrl)
}

private fun uriSetting(key: String, raw: String): URI {
    require(raw.isNotBlank()) { "Invalid --$key value: blank URL" }
    return URI.create(raw).also { uri -> require(uri.isAbsolute) { "Invalid --$key value: URL must be absolute" } }
}
