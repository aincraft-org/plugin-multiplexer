# plugin-multiplexer

[![Build](https://img.shields.io/github/actions/workflow/status/aincraft-org/plugin-multiplexer/ci.yml?branch=main&label=build)](https://github.com/aincraft-org/plugin-multiplexer/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/aincraft-org/plugin-multiplexer)](https://github.com/aincraft-org/plugin-multiplexer/releases/latest)
[![Nightly](https://img.shields.io/github/v/release/aincraft-org/plugin-multiplexer?include_prereleases&filter=nightly&label=nightly)](https://github.com/aincraft-org/plugin-multiplexer/releases/tag/nightly)
![Java](https://img.shields.io/badge/Java-25-orange)
![Velocity](https://img.shields.io/badge/Velocity-4.1.1-blue)
![Paper](https://img.shields.io/badge/Paper-26.2-blue)

A shared local development network for one Velocity proxy, a supervised lobby, and isolated Paper backends. Connect a Minecraft client to one address (`localhost:25565`) and switch between registered backends with Velocity's `/server` command.

The network is a Gradle plugin, `io.github.development-network`. Gradle tasks are the only supported public entry point. The repository root remains a quality wrapper; it does not apply the network plugin or become a runtime project.

## Requirements and build

- Java 25 (required by Velocity 4.1.1 and Paper 26.2)
- Gradle 9.7.1 (the committed root wrapper)

Run the quality and packaging gates with:

```text
./gradlew clean check
./gradlew assemble
```

The root build orchestrates the quality modules. Artifacts are written to:

```text
network/build/libs/development-network-plugin-<version>.jar
velocity-plugin/build/libs/proxy-inspector-<version>.jar
```

The network plugin embeds a separate Kotlin runtime JVM at `META-INF/development-network/runtime.jar`. A consumer extracts that verified artifact into its Gradle user home and launches it with `${java.home}/bin/java`. No source-tree path, generated launcher, or inherited registry environment is needed by a consumer.

## Consumer setup

For a local checkout, include the `network/` Gradle build in the consuming project. This is a clean composite build: the consumer needs no copied files or installed runtime.

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../plugin-multiplexer/network")
}
rootProject.name = "my-plugin"
```

`build.gradle.kts` (inside the existing `plugins` block):

```kotlin
plugins {
    id("java")
    id("io.github.development-network")
}
```

A published plugin artifact works the same way; configure the plugin repository and version according to your release process. Composite-build and published-plugin consumers execute the same embedded runtime artifact.

## Gradle tasks

The plugin registers exactly nine tasks in the `network` group:

| Task | Behavior and ownership |
|---|---|
| `runProxy` | Starts and owns the shared Velocity proxy and lobby; blocks until stopped. |
| `registerBackend` | Attaches and verifies an already-running external Paper server; never starts or stops Paper. |
| `unregisterBackend` | Removes this project's external registration; never stops Paper or changes external files. |
| `runBackend` | Builds this project's JAR, registers one managed backend, starts it, and blocks; cleans up only that backend. |
| `runNetwork` | One-project convenience mode owning proxy, lobby, and this project's managed backend. |
| `stopNetwork` | Requests controller shutdown, then performs owner- and process-identity-verified fallback cleanup. |
| `reloadNetwork` | Regenerates deterministic proxy configuration from the registry and requests a live reload. |
| `restartBackend` | Restarts one managed backend and replaces only its plugin JAR. |
| `networkStatus` | Read-only status probes for proxy, lobby, and registered backend endpoints. |

Long-lived tasks block until interrupted. Short registration, cleanup, reload, restart, and status tasks return after their operation completes. A shared network normally runs `runProxy` once, then one `runBackend` or `registerBackend` task per plugin project:

```text
./gradlew runProxy -PnetworkBase=run/network
./gradlew runBackend -PnetworkBase=run/network -PnetworkBackend=my-plugin
./gradlew registerBackend \
  -PnetworkBase=run/network \
  -PnetworkBackend=my-plugin \
  -PnetworkBackendPort=30070 \
  -PnetworkRegistrationOwner=my-plugin-agent
```

`runNetwork` is useful for one project, but is not the coordination primitive for multiple projects sharing a base.

## Properties and defaults

Existing `-P` properties remain the configuration API:

| Property | Default or requirement |
|---|---|
| `networkBase` | `run/network` |
| `networkBackend` | `project.name` |
| `networkBackendPort` | Required by `registerBackend`; must be `1024..65535`. |
| `networkProxyPort` | `25565`; `0` selects a free port. |
| `networkJarTask` | `jar` |
| `networkDevUsers` | `DEV_NETWORK_DEV_USERS`, then `dev`. |
| `networkOnlineMode` | Optional `true` or `false`; controls proxy authentication. |
| `networkRegistrationOwner` | Stable external owner token; otherwise derived from project path and backend name. |
| `networkTargetServer` | `localhost`. |
| `networkLobbyMapUrl` / `networkLobbyMapSha256` | Static map mode; both are required together. |
| `networkLobbyMapRandomUrl` | Random map mode; mutually exclusive with static mode. |

Backend names must match `[A-Za-z0-9_-]+`. The proxy defaults to port `25565`, the lobby to `30066`, and sorted backend defaults start at `30067`. Persisted `<name>.port` values remain authoritative; managed automatic allocation skips occupied and reserved sockets. `networkBackendPort` is explicit for external registration.

## Ownership and safety

One `networkBase` is one coordination domain with **one infrastructure owner** and independently owned backends. `runProxy` owns only proxy/lobby lifecycle. `runBackend` owns only its managed Paper process. `registerBackend` owns only external registration metadata. External servers remain untouched: registration never starts, stops, or deploys to Paper; it never configures or writes ops, and unregistration removes only matching metadata.

The runtime uses owner tokens, controller leases, process start identities, expected executable/work-directory checks, and authenticated control sockets. Numeric PIDs and process-name matching are never sufficient. `proxy.lock` prevents competing infrastructure controllers; `register.lock` serializes registry, port claims, ownership metadata, deterministic configuration, and reload. Cleanup is limited to state and processes owned by the current run. `stopNetwork` never stops another project's backend merely because it appears in the registry.

Before startup or client connection, mandatory offline-mode preflight independently checks the active/generated proxy, lobby, and every backend. An owned proxy may be regenerated to `online-mode = false`; an external proxy with unknown or true mode is not changed automatically. Every Paper server must have `online-mode=false`, Velocity modern forwarding enabled, and the shared `runtime/forwarding.secret`. Unknown or invalid forwarding configuration fails with an actionable path and does not modify an external server.

## Artifacts, maps, and status

The pinned artifacts are Velocity **4.1.1, build 24**, SHA-256 `846411d2d0560fed0f23496ffb89681be528d2c0650ecdcf21724d2d7bd9c1ee`, and Paper **26.2, build 119**, SHA-256 `a8c9140c3075bd7c04973e9cdc491b21bfe6bad472b674ef932a4ae0fec19629`. Artifact downloads use per-destination locks, verify existing files, stream SHA-256 into a same-directory temporary file, compare the exact expected checksum, atomically rename only verified content, and clean failed temporary files.

Lobby map installation is immutable once `runtime/lobby/world/level.dat` exists. Static mode requires URL plus exact SHA-256; random mode requires only its random URL and is mutually exclusive with static variables. No map keeps the generated empty world. Downloads are locally checksum-verified and random selections are not retained as reusable caches.

ZIP validation occurs before extraction and rejects absolute paths, `..` path traversal components, backslashes, Windows drive paths, duplicate entries, symlinks, device/FIFO/socket/special files, malformed archives, unsafe temporary-directory escapes, and layouts lacking `level.dat` at the archive root or exactly one top-level world directory. Extraction is performed in a temporary sibling and installed atomically only after complete validation.

`networkStatus` reports reachability and player/version data for each endpoint using the Minecraft handshake/status protocol. Proxy status is the proxy's own response and proves reachability, not routing. Prove routing with a real login to `localhost:25565`, then `/server <name>`.

## Proxy Inspector

`velocity-plugin/` is a standalone Velocity 4.1.1 plugin. Copy its built JAR into the proxy's `plugins/` directory through your normal deployment process. It provides `/servers` (alias `/serverlist`) and `/plugins` (alias `/pluginlist`). It does not inspect Paper plugin directories. Managed backends receive `networkDevUsers` through `ops.json` using the exact offline UUID algorithm; external Paper servers are not modified and must be administered through their own permission system.

## Agent split

Start infrastructure once from the controller project:

```text
./gradlew runProxy
```

Each plugin project then chooses exactly one backend mode:

```text
./gradlew runBackend
./gradlew registerBackend -PnetworkBackend=hero -PnetworkBackendPort=30070 -PnetworkRegistrationOwner=agent-hero
```

The first mode owns and cleans up managed Paper. The second verifies and records an already-running external Paper server and returns without lifecycle changes. Neither backend task can claim the shared proxy.
