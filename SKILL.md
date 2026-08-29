---
name: development-network
description: Use when configuring the Gradle development-network plugin: one Velocity proxy, a supervised lobby, and isolated Paper backends behind one client address.
---

# Gradle Development Network

This repository provides the `io.github.development-network` Gradle plugin. It owns a local development network with one Velocity proxy, one lobby, and isolated Paper backends. A client connects to `localhost:25565` and uses Velocity's `/server <name>` command to switch backends.

Gradle tasks are the only public entry point. The repository root is a quality wrapper and does not apply the network plugin. Consumer builds use the embedded runtime from the `network/` composite build or a published plugin; consumers never depend on source-tree launchers or inherited registry state.

## Pinned components

| Component | Version/build | SHA-256 |
|---|---|---|
| Velocity | 4.1.1, build 24 (Java 25+, config-version 2.8) | `846411d2d0560fed0f23496ffb89681be528d2c0650ecdcf21724d2d7bd9c1ee` |
| Paper | 26.2, build 119 (Java 25+) | `a8c9140c3075bd7c04973e9cdc491b21bfe6bad472b674ef932a4ae0fec19629` |

Update each version, build, and checksum together when pins change. Do not allow proxy and Paper versions to drift. Java 25 is required for both servers.

## Consumer setup

For a checkout, include the network Gradle build from the consuming project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../plugin-multiplexer/network")
}
rootProject.name = "my-plugin"
```

Apply the plugin inside the consuming project's existing plugins block:

```kotlin
plugins {
    id("java")
    id("io.github.development-network")
}
```

The same setup can use a published plugin version through the configured plugin repository. Composite-build and published-plugin consumers execute the same embedded runtime JAR. The plugin extracts `META-INF/development-network/runtime.jar`, verifies its SHA-256 in a content-addressed Gradle-user-home cache, locks extraction, and launches it with `${java.home}/bin/java`. A failed extraction removes only its own temporary file; a verified cache entry is never overwritten.

## Remote CI

On a clean GitHub Actions checkout, CI uses Java 25 and the committed Gradle wrapper. It runs `./gradlew clean check` and then `./gradlew assemble`. This describes the remote build sequence; it does not claim any current remote status.

The runtime uses direct JDK APIs for downloads, archive handling, configuration, process control, sockets, and status probes. It does not use a command interpreter. Explicit typed task arguments form the plugin/runtime protocol; documented environment fallbacks are read only during Gradle configuration.

## Public tasks

Exactly nine tasks are registered in the `network` group:

| Task | Contract |
|---|---|
| `runProxy` | Own the shared proxy and lobby; block until stopped. |
| `registerBackend` | Verify and register an already-running external Paper server; never starts or stops Paper. |
| `unregisterBackend` | Remove this project's matching external registration; never changes or stops Paper. |
| `runBackend` | Build and deploy this project's JAR, register one managed backend, start Paper, block, and clean up that backend on exit. |
| `runNetwork` | One-project convenience mode owning proxy, lobby, and this project's managed backend. |
| `stopNetwork` | Request authenticated controller shutdown, then use owner-verified fallback cleanup when necessary. |
| `reloadNetwork` | Regenerate deterministic proxy configuration and request a live reload. |
| `restartBackend` | Restart one managed backend on its persisted port and replace only its plugin JAR. |
| `networkStatus` | Probe proxy, lobby, and registered backends and report reachability/player/version data. |

Long-lived tasks are blocking tasks; registration, unregistration, stop, reload, restart, and status return after completion. For a shared base, run `runProxy` once and let each plugin project choose `runBackend` or `registerBackend`:

```text
./gradlew runProxy -PnetworkBase=run/network
./gradlew runBackend -PnetworkBase=run/network -PnetworkBackend=my-plugin
./gradlew registerBackend \
  -PnetworkBase=run/network \
  -PnetworkBackend=my-plugin \
  -PnetworkBackendPort=30070 \
  -PnetworkRegistrationOwner=agent-my-plugin
```

`runNetwork` is for a single project's complete stack and is not a multi-project coordination primitive.

### Dedicated shared network / external backend

Use a dedicated `networkBase` for this coordination domain, and start its controller once with explicit, non-conflicting infrastructure ports:

```text
./gradlew runProxy \
  -PnetworkBase=run/dedicated-network \
  -PnetworkProxyPort=25565 \
  -PnetworkLobbyPort=30069
```

With an already-running external Paper server listening on backend port `25566`, register it against the active controller. Supply the external Paper files through `networkServerDir` and keep the registration owner stable across runs:

```text
./gradlew registerBackend \
  -PnetworkBase=run/dedicated-network \
  -PnetworkLobbyPort=30069 \
  -PnetworkBackend=external-paper \
  -PnetworkBackendPort=25566 \
  -PnetworkServerDir=run/external-paper \
  -PnetworkRegistrationOwner=external-paper-owner
```

Registration uses the active controller's existing proxy configuration, requires `networkLobbyPort` and `networkServerDir` to identify the backend and its files, and never edits or stops Paper. Inspect the endpoints afterward:

```text
./gradlew networkStatus \
  -PnetworkBase=run/dedicated-network \
  -PnetworkProxyPort=25565 \
  -PnetworkLobbyPort=30069
```

`networkStatus` proves endpoint reachability but does not prove client routing. Connect a client to `localhost:25565` and use `/server external-paper` to prove routing.

| Property | Default/requirement |
|---|---|
| `networkBase` | `run/network` |
| `networkBackend` | `project.name` |
| `networkBackendPort` | Required for external registration; `1024..65535`. |
| `networkProxyPort` | `25565`; `0` selects a free port. |
| `networkLobbyPort` | `30066`; infrastructure lobby port, required to be `1024..65535` and distinct from proxy/backend ports. |
| `networkTimeout` | `240` seconds; readiness and status-probe timeout. |
| `networkShutdownTimeout` | `30` seconds; graceful shutdown wait. |
| `networkControlTimeout` | `5` seconds; authenticated controller request timeout. |
| `networkServerDir` | Optional external Paper directory; otherwise `runtime/external/<name>` under `networkBase`. |
| `networkJarTask` | `jar`. |
| `networkDevUsers` | `DEV_NETWORK_DEV_USERS`, then `dev`. |
| `networkOnlineMode` | Optional `true` or `false`, mapped to proxy online mode. |
| `networkRegistrationOwner` | Stable external owner token, otherwise derived from project path and backend name. |
| `networkTargetServer` | `localhost`. |
| `networkLobbyMapUrl` and `networkLobbyMapSha256` | Static map mode; both required together. |
| `networkLobbyMapRandomUrl` | Random map mode; mutually exclusive with static variables. |

Configure a static lobby map on the infrastructure task with its exact checksum:

```text
./gradlew runProxy \
  -PnetworkLobbyMapUrl=https://maps.example.invalid/lobby.zip \
  -PnetworkLobbyMapSha256=<sha256>
```

For a one-time random map selection, use the mutually exclusive random-map property:

```text
./gradlew runProxy \
  -PnetworkLobbyMapRandomUrl=https://maps.example.invalid/random.zip
```

Replace the example URLs and checksum with the map source selected for the development network.

Backend names match `[A-Za-z0-9_-]+`. The compatibility state remains plain files under the base:

```text
runtime/backends.txt
runtime/<name>.port
runtime/<name>.owner
runtime/<name>.pid
runtime/<name>.ready
runtime/<name>.auto-dir
runtime/proxy.owner
runtime/proxy.pid
runtime/proxy.ready
runtime/proxy.lock
runtime/register.lock
runtime/velocity.toml
runtime/forwarding.secret
runtime/proxy.control
runtime/proxy.control.token
```

The proxy uses `25565`, the lobby uses `30066`, and sorted backend defaults begin at `30067 + index`. Persisted backend ports win across reindexing. Explicit values come next; managed automatic allocation skips occupied sockets and all ports reserved in the current registry transition. Duplicate names, duplicate ports, invalid ports, proxy/lobby collisions, and unavailable external listeners fail before mutation.

## Ownership and lifecycle

One `networkBase` is one coordination domain with **one infrastructure owner** and independently owned managed backends. `runProxy` and `runNetwork` are the only operations that can own proxy/lobby lifecycle. `runBackend` owns exactly one managed Paper process and never starts, restarts, or stops proxy/lobby. `registerBackend` owns only one external registration's metadata. External servers remain untouched.

External registration requires a stable owner token, a live controller, an explicit port, and an already-running Paper server. It verifies reachability, `server.properties` `online-mode=false`, and `config/paper-global.yml` Velocity modern forwarding with `online-mode=false` and the shared forwarding secret. Missing or unknown configuration fails with the exact path and required setting; registration writes only registry/name/port/owner/ready metadata and requests a reload. It never starts, stops, or deploys to Paper; it never configures or writes ops. `unregisterBackend` removes only matching external metadata.

Managed startup deploys the configured `Jar.archiveFile` after deleting stale JARs from that isolated backend plugin directory. It generates managed Paper configuration and ops, registers the owner, starts Paper, waits for readiness, and blocks. Normal exit, cancellation, or startup failure unregisters and stops only the matching managed process. `restartBackend` stops that managed process, clears readiness, replaces only its plugin JAR, and restarts it on the persisted port; proxy, lobby, external servers, and other backends stay up.

The proxy controller acquires `proxy.lock`, resolves registry and ports, runs preflight, fetches pinned artifacts, writes deterministic configuration, creates the authenticated control socket, starts proxy and lobby, and serves shutdown/reload requests. Unexpected lobby exits are supervised and restarted after stale markers are cleared and a two-second delay; intentional shutdown disables that loop. Full mode additionally starts only its controller-owned managed backend. Shutdown clears owner-matching state and releases locks.

Leases record a run token, role, controller PID and process start identity, child identities, bound port, and timestamps. Numeric PIDs are never sufficient. Before signaling or cleanup, verify process start identity and expected executable/work directory where available. `stopNetwork` requests authenticated controller shutdown and waits for lease removal; fallback cleanup is limited to infrastructure and managed processes explicitly owned by the addressed run. It never uses process-name matching or an unverified PID and never stops another project's backend merely because it appears in the registry.

`runtime/register.lock` serializes registry transitions, port claims, ownership metadata, deterministic configuration, and reload. A registration rolls back only state created by its own run token if startup or final commit fails; a concurrent owner cannot replace a name or port between registration, boot, and commit.

## Offline mode and forwarding preflight

Preflight is mandatory before starting any component or connecting a client. Check the active/generated Velocity configuration, the lobby, and every backend independently:

1. An owned proxy must contain `online-mode = false`; the runtime may regenerate its own configuration and then recheck it.
2. An external proxy must be inspected in its active configuration. Unknown or true mode is an actionable failure; the runtime never changes an external proxy automatically.
3. Lobby and managed Paper `server.properties` must contain `online-mode=false`. External Paper must be checked in its live configuration.
4. Every Paper server must enable Velocity modern forwarding, set `online-mode=false`, contain the shared `runtime/forwarding.secret`, and have `spigot.yml` `settings.bungeecord: false`.
5. Start and allow client connection only after proxy, lobby, and every backend pass.

The proxy's deterministic `velocity.toml` preserves modern forwarding, the selected proxy online mode, target host, lobby-first `try` list, sorted backend entries, and persisted ports. `forwarding.secret` is a fixed development secret, not a production credential. External configuration is never generated or overwritten.

## Artifact and lobby map safety

`ArtifactFetcher` uses a per-destination blocking lock, verifies an existing file before reuse, removes an invalid destination, downloads to a same-directory temporary file while streaming SHA-256, compares the exact expected checksum, atomically renames only verified bytes, and cleans failed temporary files.

`LobbyMapInstaller` supports three modes:

- Static mode requires `networkLobbyMapUrl` and its exact `networkLobbyMapSha256`.
- Random mode requires only `networkLobbyMapRandomUrl` and is mutually exclusive with static mode.
- With no map properties, the runtime keeps the generated empty world.

Installation is immutable once `runtime/lobby/world/level.dat` exists. Dynamic downloads are verified locally before extraction and are not retained as a reusable random selection cache after successful installation. A new map requires deliberately removing the existing world.

ZIP validation happens before extraction and rejects absolute paths, `..` path traversal components, backslashes, Windows drive-style paths, duplicate entries, symlink entries, device/FIFO/socket/other special file types, malformed archives, and archives that could escape the temporary extraction directory. The archive must contain `level.dat` at its root or exactly one top-level world directory. Entry metadata, including external attributes, is inspected. Extraction occurs in a temporary sibling directory and is installed atomically only after complete validation and successful extraction.

## Status and routing

`networkStatus` reads persisted registry and ports, probes each endpoint using the Minecraft handshake/status protocol, prints one report per endpoint, and returns nonzero if a required probe fails. A proxy response proves proxy reachability only: it is the proxy's own status, not backend routing. Prove routing with a real login to `localhost:25565`, then `/server <name>`.

## Developer users and Proxy Inspector

Managed Paper receives `networkDevUsers` in `ops.json` at operator level 4 using `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`. Velocity permissions are separate: when the Proxy Inspector plugin is installed, these users receive the development-only `velocity.command.*` and related admin nodes. External Paper servers remain fully external and must be administered by their own permission plugin or `/op` flow.

`velocity-plugin/` provides `/servers` (alias `/serverlist`) and `/plugins` (alias `/pluginlist`). It reports proxy-side plugins and registered-server reachability but cannot inspect backend plugin directories without a backend reporting plugin.

## Agent split

The recommended ownership split is:

1. A controller project runs `runProxy` once for the shared `networkBase`.
2. Each plugin project runs `runBackend` when this runtime should own its Paper process.
3. Each plugin project runs `registerBackend` when another process already owns Paper; supply `networkBackendPort` and a stable `networkRegistrationOwner`.
4. Agents use `reloadNetwork`, `networkStatus`, `restartBackend`, `stopNetwork`, and `unregisterBackend` only for their documented ownership scope.

Never use a backend task to claim shared infrastructure. Never treat status as proof of routing. Preserve external server files, processes, permissions, and lifecycle outside this runtime.
