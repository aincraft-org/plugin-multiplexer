package io.github.developmentnetwork.runtime.registry

import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.model.BackendNames
import io.github.developmentnetwork.runtime.model.BackendRegistration
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.model.ProcessIdentity
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.BackendStatePaths
import io.github.developmentnetwork.runtime.state.FileLocks
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Path
import java.time.Instant
import java.nio.file.Files
/** Persistent registry and serialized ownership transitions for one runtime layout. */
class RegistryStore(private val layout: RuntimeLayout) {
    private val locks = FileLocks(layout)

    private companion object {
        val PERSISTED_STATE_SUFFIXES = listOf(
            ".port", ".owner", ".mode", ".pid", ".start", ".executable", ".working-directory",
        )
    }

    /** Read the validated, sorted, unique backend names from backends.txt. */
    fun readNames(): List<BackendName> = locks.withRegistrationLock {
        readNamesUnlocked()
    }

    private fun readNamesUnlocked(): List<BackendName> {
        val lines = AtomicFiles.readLinesIfExists(layout.registryFile) ?: return emptyList()
        return lines.asSequence()
            .map { it.removeSuffix("\r") }
            .filter { it.isNotEmpty() }
            .map(BackendNames::validate)
            .distinct()
            .sortedBy { it.value }
            .toList()
    }

    /** Atomically persist one backend name per line in canonical order. */
    fun writeNames(names: Iterable<BackendName>) {
        val canonical = names.distinct().sortedBy { it.value }
        locks.withRegistrationLock {
            writeNamesUnlocked(canonical)
        }
    }

    fun readRegistrations(): List<BackendRegistration> = locks.withRegistrationLock {
        persistedNamesUnlocked().map { name ->
            readRegistrationUnlocked(name)
                ?: error("Registry entry $name has no complete ownership state")
        }
    }

    /** Include state-file claims even when a crash or manual edit hid their name. */
    private fun persistedNamesUnlocked(): List<BackendName> {
        val names = readNamesUnlocked().toMutableSet()
        if (!Files.isDirectory(layout.runtimeDir)) return names.sortedBy { it.value }
        Files.list(layout.runtimeDir).use { entries ->
            entries.forEach { path ->
                val fileName = path.fileName.toString()
                PERSISTED_STATE_SUFFIXES.firstOrNull { fileName.endsWith(it) }?.let { suffix ->
                    val rawName = fileName.removeSuffix(suffix)
                    if (rawName.isNotEmpty()) names += BackendNames.validate(rawName)
                }
            }
        }
        return names.sortedBy { it.value }
    }

    /** Read one registration, or null when no state exists for the name. */
    fun readRegistration(name: BackendName): BackendRegistration? =
        locks.withRegistrationLock { readRegistrationUnlocked(name) }

    private fun readRegistrationUnlocked(name: BackendName): BackendRegistration? {
        val state = layout.backend(name)
        val hasPort = Files.exists(state.port)
        val hasOwner = Files.exists(state.owner)
        if (!hasPort && !hasOwner) return null
        require(hasPort && hasOwner) {
            "Incomplete registration state for $name: both .port and .owner are required"
        }

        val port = AtomicFiles.read(state.port).trim().toIntOrNull()
            ?: error("Invalid persisted port for $name")
        val ownerState = readOwnerState(state.owner, name)
        val owner = ownerState.owner
        val mode = if (Files.exists(state.mode)) {
            runCatching {
                OwnershipMode.valueOf(readSingleLine(state.mode, "mode for $name").uppercase())
            }.getOrElse { error("Invalid persisted ownership mode for $name") }
        } else {
            // State created by the original shell contract predates an explicit mode;
            // use its owner metadata when available, otherwise managed is historical default.
            ownerState.mode ?: OwnershipMode.MANAGED
        }
        val process = when (mode) {
            OwnershipMode.EXTERNAL -> {
                require(
                    !Files.exists(state.pid) &&
                        !Files.exists(state.startIdentity) &&
                        !Files.exists(state.executable) &&
                        !Files.exists(state.workingDirectory),
                ) {
                    "External registration $name must not have process identity"
                }
                null
            }
            OwnershipMode.MANAGED -> readProcessIdentity(state)
        }
        return BackendRegistration(name, port, owner, mode, process)
    }

    private fun readOwnerState(path: Path, name: BackendName): OwnerState {
        val content = AtomicFiles.read(path)
        val lines = content.lineSequence()
            .map { it.removeSuffix("\r") }
            .toList()
        val ownerLine = lines.firstOrNull { it.startsWith("owner=") }
        val owner = ownerLine?.removePrefix("owner=")
            ?: content.trimEnd('\r', '\n')
        require(owner.isNotBlank()) { "Invalid blank owner for $name" }
        val mode = lines.firstOrNull { it.startsWith("mode=") }
            ?.removePrefix("mode=")
            ?.let { raw ->
                runCatching { OwnershipMode.valueOf(raw.uppercase()) }
                    .getOrElse { error("Invalid persisted ownership mode for $name") }
            }
        return OwnerState(owner, mode)
    }

    private data class OwnerState(val owner: String, val mode: OwnershipMode?)

    fun readRegistration(raw: String): BackendRegistration? =
        readRegistration(BackendNames.validate(raw))

    /**
     * Register or update a backend owned by [registration.owner]. A different owner
     * cannot replace an existing name, and no two names may claim one port.
     */
    fun register(registration: BackendRegistration) {
        locks.withRegistrationLock {
            val namesBefore = readNamesUnlocked()
            val persistedNames = persistedNamesUnlocked()
            val existing = readRegistrationUnlocked(registration.name)
            if (registration.name in namesBefore && existing == null) {
                error("Backend ${registration.name} is already registered without owner metadata")
            }
            if (existing != null && existing.owner != registration.owner) {
                error("Backend ${registration.name} is already owned by ${existing.owner}")
            }

            persistedNames.map { name ->
                readRegistrationUnlocked(name)
                    ?: error("Registry entry $name has no complete ownership state")
            }.forEach { current ->
                if (current.name != registration.name && current.port == registration.port) {
                    error("Backend port ${registration.port} is already claimed by ${current.name}")
                }
            }

            writeRegistrationState(registration)
            val names = (namesBefore + registration.name).distinct().sortedBy { it.value }
            writeNamesUnlocked(names)
        }
    }
    /** Remove a registration only when its owner token matches. */
    fun unregister(name: BackendName, owner: String): Boolean =
        locks.withRegistrationLock {
            val existing = readRegistrationUnlocked(name) ?: return@withRegistrationLock false
            require(existing.owner == owner) {
                "Backend $name is owned by ${existing.owner}, not $owner"
            }
            deleteRegistrationState(layout.backend(name), existing.mode)
            writeNamesUnlocked(readNamesUnlocked().filterNot { it == name })
            true
        }

    fun unregister(raw: String, owner: String): Boolean =
        unregister(BackendNames.validate(raw), owner)

    fun remove(name: BackendName, owner: String): Boolean = unregister(name, owner)

    private fun readProcessIdentity(state: BackendStatePaths): ProcessIdentity? {
        if (!Files.exists(state.pid)) return null
        val pid = AtomicFiles.read(state.pid).trim().toLongOrNull()
            ?: error("Invalid persisted process ID for ${state.name}")
        val start = optionalText(state.startIdentity)?.let {
            runCatching { Instant.parse(it) }.getOrElse {
                error("Invalid persisted process start identity for ${state.name}")
            }
        }
        val executable = optionalText(state.executable)?.let(Path::of)
        val workingDirectory = optionalText(state.workingDirectory)?.let(Path::of)
        return ProcessIdentity(pid, start, executable, workingDirectory)
    }

    private fun writeRegistrationState(registration: BackendRegistration) {
        val state = layout.backend(registration.name)
        AtomicFiles.write(state.port, "${registration.port}\n")
        AtomicFiles.write(state.owner, "${registration.owner}\n")
        AtomicFiles.write(state.mode, "${registration.mode.name}\n")
        if (registration.mode == OwnershipMode.MANAGED && registration.process != null) {
            val process = registration.process
            AtomicFiles.write(state.pid, "${process.pid}\n")
            writeOptional(state.startIdentity, process.startInstant?.toString())
            writeOptional(state.executable, process.executable?.toString())
            writeOptional(state.workingDirectory, process.workingDirectory?.toString())
        } else {
            deleteProcessState(state)
        }
    }

    private fun writeOptional(path: Path, value: String?) {
        if (value == null) Files.deleteIfExists(path) else AtomicFiles.write(path, "$value\n")
    }

    private fun deleteRegistrationState(state: BackendStatePaths, mode: OwnershipMode) {
        Files.deleteIfExists(state.port)
        Files.deleteIfExists(state.owner)
        Files.deleteIfExists(state.mode)
        // Process identity and auto-directory markers are managed-owned state.
        if (mode == OwnershipMode.MANAGED) {
            deleteProcessState(state)
            Files.deleteIfExists(state.autoDir)
        }
        // Readiness is harness metadata for both ownership modes.
        Files.deleteIfExists(state.ready)
    }

    private fun deleteProcessState(state: BackendStatePaths) {
        Files.deleteIfExists(state.pid)
        Files.deleteIfExists(state.startIdentity)
        Files.deleteIfExists(state.executable)
        Files.deleteIfExists(state.workingDirectory)
    }

    private fun readSingleLine(path: Path, description: String): String =
        AtomicFiles.read(path).trimEnd('\r', '\n').also {
            require(it.isNotBlank()) { "Invalid blank $description" }
        }

    private fun optionalText(path: Path): String? =
        if (Files.exists(path)) AtomicFiles.read(path).trimEnd('\r', '\n').ifEmpty { null } else null

    private fun writeNamesUnlocked(names: Iterable<BackendName>) {
        AtomicFiles.writeLines(layout.registryFile, names.map { it.value })
    }
}

