package io.github.developmentnetwork.runtime.model

import java.nio.file.Path
import java.time.Instant

/** A backend name that is safe to use as a runtime state-file component. */
data class BackendName(val value: String) {
    init {
        require(NAME_PATTERN.matches(value)) {
            "Invalid backend name '$value'; expected one or more ASCII letters, digits, '_' or '-'."
        }
    }

    override fun toString(): String = value

    private companion object {
        val NAME_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}

/** Factory for backend names read from task input or persisted state. */
object BackendNames {
    fun validate(raw: String): BackendName = BackendName(raw)
}

enum class OwnershipMode {
    MANAGED,
    EXTERNAL,
}

/** Identity captured with a process lease. A nullable field is unavailable on the host. */
data class ProcessIdentity(
    val pid: Long,
    val startInstant: Instant?,
    val executable: Path?,
    val workingDirectory: Path?,
) {
    init {
        require(pid > 0) { "Process ID must be positive: $pid" }
    }
}

/** Complete persisted ownership record for one registered backend. */
data class BackendRegistration(
    val name: BackendName,
    val port: Int,
    val owner: String,
    val mode: OwnershipMode,
    val process: ProcessIdentity?,
) {
    init {
        require(port in 1024..65535) { "Backend port must be in 1024..65535: $port" }
        require(port != 25565 && port != 30066) {
            "Backend port collides with the proxy/lobby port: $port"
        }
        require(owner.isNotBlank()) { "Backend owner must not be blank" }
        require('\n' !in owner && '\r' !in owner) { "Backend owner must be a single line" }
        if (mode == OwnershipMode.EXTERNAL) {
            require(process == null) { "External registrations cannot carry process identity" }
        }
    }
}
