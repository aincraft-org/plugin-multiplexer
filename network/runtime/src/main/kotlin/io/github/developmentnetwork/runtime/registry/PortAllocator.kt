package io.github.developmentnetwork.runtime.registry

import io.github.developmentnetwork.runtime.model.BackendName

/** Deterministic port rules shared by registration and managed startup. */
class PortAllocator {
    fun allocate(
        name: BackendName,
        registry: Collection<BackendName>,
        persisted: Int? = null,
        explicit: Int? = null,
        occupied: Set<Int> = emptySet(),
        reserved: Set<Int> = emptySet(),
        occupiedProbe: (Int) -> Boolean = { false },
    ): Int {
        val names = registry.distinct().sortedBy { it.value }
        val index = names.indexOf(name)
        require(index >= 0) { "Backend $name is not present in the registry" }

        val claims = occupied + reserved
        persisted?.let {
            validatePort(it, "persisted port for $name")
            rejectCollision(it, claims, "persisted port for $name")
            return it
        }
        explicit?.let {
            validatePort(it, "explicit port for $name")
            rejectCollision(it, claims, "explicit port for $name")
            return it
        }

        var candidate = DEFAULT_BACKEND_PORT + index
        while (candidate <= MAX_PORT) {
            if (candidate != PROXY_PORT && candidate != LOBBY_PORT &&
                candidate !in claims && !occupiedProbe(candidate)
            ) {
                return candidate
            }
            candidate += 1
        }
        throw IllegalStateException("No free backend port available for $name")
    }

    fun allocate(
        name: String,
        registry: Collection<String>,
        persisted: Int? = null,
        explicit: Int? = null,
        occupied: Set<Int> = emptySet(),
        reserved: Set<Int> = emptySet(),
        occupiedProbe: (Int) -> Boolean = { false },
    ): Int = allocate(
        BackendName(name),
        registry.map(::BackendName),
        persisted,
        explicit,
        occupied,
        reserved,
        occupiedProbe,
    )

    private fun validatePort(port: Int, description: String) {
        require(port in MIN_PORT..MAX_PORT) {
            "$description must be in $MIN_PORT..$MAX_PORT: $port"
        }
        require(port != PROXY_PORT && port != LOBBY_PORT) {
            "$description collides with the proxy/lobby port: $port"
        }
    }

    private fun rejectCollision(port: Int, claims: Set<Int>, description: String) {
        require(port !in claims) { "$description is already occupied or reserved: $port" }
    }

    companion object {
        const val MIN_PORT: Int = 1024
        const val MAX_PORT: Int = 65535
        const val PROXY_PORT: Int = 25565
        const val LOBBY_PORT: Int = 30066
        const val DEFAULT_BACKEND_PORT: Int = 30067
    }
}
