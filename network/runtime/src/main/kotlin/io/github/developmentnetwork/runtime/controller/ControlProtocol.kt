package io.github.developmentnetwork.runtime.controller

/** Commands accepted by a live runtime controller. */
sealed interface ControlCommand {
    data object Reload : ControlCommand
    data object Shutdown : ControlCommand
}

/** Result returned by a controller for one authenticated request. */
data class ControlResponse(
    val ok: Boolean,
    val message: String = "",
) {
    val success: Boolean get() = ok
    val accepted: Boolean get() = ok
    val error: String? get() = if (ok) null else message

    companion object {
        fun success(message: String = ""): ControlResponse = ControlResponse(true, message)
        fun failure(message: String): ControlResponse = ControlResponse(false, message)
    }
}

internal object ControlWire {
    const val RELOAD = "reload"
    const val SHUTDOWN = "shutdown"
    const val AUTHENTICATION_FAILED = "authentication failed"

    fun encodeCommand(command: ControlCommand): String = when (command) {
        ControlCommand.Reload -> RELOAD
        ControlCommand.Shutdown -> SHUTDOWN
    }

    fun decodeCommand(value: String): ControlCommand? = when (value) {
        RELOAD -> ControlCommand.Reload
        SHUTDOWN -> ControlCommand.Shutdown
        else -> null
    }

    fun encodeResponse(response: ControlResponse): String =
        buildString {
            append(if (response.ok) "ok" else "error")
            append('\n')
            append(response.message.replace('\r', ' ').replace('\n', ' '))
            append('\n')
        }

    fun decodeResponse(status: String, message: String): ControlResponse =
        ControlResponse(status == "ok", message)
}
