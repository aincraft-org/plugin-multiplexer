package io.github.developmentnetwork.runtime.state

import io.github.developmentnetwork.runtime.model.BackendName
import java.nio.file.Path

/** Canonical paths for one development-network coordination domain. */
data class RuntimeLayout(val base: Path) {
    val runtimeDir: Path = base.resolve("runtime")
    val binariesDir: Path = base.resolve("binaries")
    val logsDir: Path = base.resolve("logs")

    val velocityConfig: Path = runtimeDir.resolve("velocity.toml")
    val forwardingSecret: Path = runtimeDir.resolve("forwarding.secret")
    val registryFile: Path = runtimeDir.resolve("backends.txt")
    val proxyOwner: Path = runtimeDir.resolve("proxy.owner")
    val proxyPid: Path = runtimeDir.resolve("proxy.pid")
    val proxyReady: Path = runtimeDir.resolve("proxy.ready")
    val proxyLock: Path = runtimeDir.resolve("proxy.lock")
    val registrationLock: Path = runtimeDir.resolve("register.lock")
    val proxyControl: Path = runtimeDir.resolve("proxy.control")
    val proxyControlToken: Path = runtimeDir.resolve("proxy.control.token")

    /** Return all state paths whose filename is derived from a validated backend name. */
    fun backend(name: BackendName): BackendStatePaths = BackendStatePaths(
        name = name,
        port = runtimeDir.resolve("${name.value}.port"),
        owner = runtimeDir.resolve("${name.value}.owner"),
        mode = runtimeDir.resolve("${name.value}.mode"),
        pid = runtimeDir.resolve("${name.value}.pid"),
        ready = runtimeDir.resolve("${name.value}.ready"),
        autoDir = runtimeDir.resolve("${name.value}.auto-dir"),
        startIdentity = runtimeDir.resolve("${name.value}.start"),
        executable = runtimeDir.resolve("${name.value}.executable"),
        workingDirectory = runtimeDir.resolve("${name.value}.working-directory"),
    )

    fun backend(raw: String): BackendStatePaths = backend(BackendName(raw))

    fun backendPaths(name: BackendName): BackendStatePaths = backend(name)
    fun backendPaths(raw: String): BackendStatePaths = backend(raw)
    fun backendState(name: BackendName): BackendStatePaths = backend(name)
    fun backendState(raw: String): BackendStatePaths = backend(raw)

    fun backendPort(name: BackendName): Path = backend(name).port
    fun backendOwner(name: BackendName): Path = backend(name).owner
    fun backendMode(name: BackendName): Path = backend(name).mode
    fun backendPid(name: BackendName): Path = backend(name).pid
    fun backendReady(name: BackendName): Path = backend(name).ready
    fun backendAutoDir(name: BackendName): Path = backend(name).autoDir

    fun backendPort(raw: String): Path = backendPort(BackendName(raw))
    fun backendOwner(raw: String): Path = backendOwner(BackendName(raw))
    fun backendMode(raw: String): Path = backendMode(BackendName(raw))
    fun backendPid(raw: String): Path = backendPid(BackendName(raw))
    fun backendReady(raw: String): Path = backendReady(BackendName(raw))
    fun backendAutoDir(raw: String): Path = backendAutoDir(BackendName(raw))
}

data class BackendStatePaths(
    val name: BackendName,
    val port: Path,
    val owner: Path,
    val mode: Path,
    val pid: Path,
    val ready: Path,
    val autoDir: Path,
    val startIdentity: Path,
    val executable: Path,
    val workingDirectory: Path,
)
