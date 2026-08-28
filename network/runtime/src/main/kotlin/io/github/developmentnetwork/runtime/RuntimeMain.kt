package io.github.developmentnetwork.runtime

import kotlin.system.exitProcess

/** The command envelope passed from the Gradle plugin to the embedded runtime. */
data class RuntimeRequest(val command: String, val settings: Map<String, String>)

private val supportedCommands = setOf(
    "runProxy",
    "registerBackend",
    "unregisterBackend",
    "runBackend",
    "runNetwork",
    "stopNetwork",
    "reloadNetwork",
    "restartBackend",
    "networkStatus",
    "serve-proxy",
    "serve-full",
    "serve-backend",
    "register-external",
    "unregister-external",
    "stop-network",
    "reload-network",
    "restart-backend",
    "network-status",
)

/** Parses one command token followed by zero or more `--key=value` settings. */
fun parseRuntimeRequest(arguments: List<String>): RuntimeRequest {
    require(arguments.isNotEmpty()) { "Missing runtime command" }

    val command = arguments.first()
    require(command.isNotEmpty() && !command.startsWith("--")) {
        "Missing runtime command"
    }
    require(command in supportedCommands) {
        "Unknown command: $command"
    }

    val settings = LinkedHashMap<String, String>()
    arguments.drop(1).forEach { argument ->
        require(argument.startsWith("--")) {
            "Expected a --key=value setting after command $command, got: $argument"
        }
        val separator = argument.indexOf('=', startIndex = 2)
        require(separator > 2) {
            "Expected a --key=value setting, got: $argument"
        }
        val key = argument.substring(2, separator)
        require(key !in settings) {
            "Duplicate runtime setting: $key"
        }
        settings[key] = argument.substring(separator + 1)
    }
    return RuntimeRequest(command, settings.toMap())
}

/**
 * Parses the request envelope and returns a process exit code.
 *
 * Runtime command implementations are introduced in later migration tasks. For now,
 * a recognized envelope is accepted without performing a command operation.
 */
fun runRuntime(arguments: List<String>): Int = try {
    parseRuntimeRequest(arguments)
    0
} catch (error: IllegalArgumentException) {
    System.err.println("Runtime request error: ${error.message}")
    2
}

fun main(arguments: Array<String>) {
    exitProcess(runRuntime(arguments.toList()))
}
