---
name: development-network
description: Use when setting up a local Velocity proxy development network — a proxy with a basic lobby server plus one or more isolated dev Paper servers behind it — so a user connects to ONE address (localhost:25565) and multiplexes between different plugin development environments with the built-in /server command instead of connecting to multiple servers. Triggers include booting a dev Velocity network, the runNetwork Gradle task and includeBuild wiring, per-plugin dev servers, drop-in runtime/auto backends, proxy multiplexing, the /server switch command, BACKENDS registration, connecting an external runServer to the network (EXTERNAL_BACKENDS), DEV_USERS operator setup and ops.json offline UUIDs, proxy permission nodes (velocity.command.*, /lpv), velocity.toml, forwarding.secret, paper-global.yml proxies.velocity, restarting one backend after a plugin rebuild, and cleanly stopping the whole network.
---

# Velocity Dev Network

A local harness: **one Velocity proxy, one basic lobby Paper server, N isolated dev Paper backends**. The user connects Minecraft to `localhost:25565` only, and hops between backends with the built-in `/server` command — no reconnecting to multiple ports, no port juggling.
> This harness is also available as the standalone repository [`aincraft-org/plugin-multiplexer`](https://github.com/aincraft-org/plugin-multiplexer). In the standalone repo the harness contents sit at the root, so paths that begin with `development-network/` in this skill should be run from the repo root with that prefix removed (for example, `bin/dev-network.sh` instead of `development-network/bin/dev-network.sh`).

Version pins verified 2026-08-27 against the official PaperMC fill API (https://fill.papermc.io/v3/projects/velocity, .../projects/paper), the getting-started guide, and the [player information forwarding](https://docs.papermc.io/velocity/player-information-forwarding/) docs.

## Pinned versions

| Component | Version | SHA-256 (pinned artifact) |
|---|---|---|
| Velocity | **4.1.1, build 24** (Java 25+; `config-version = "2.8"`) | `846411d2d0560fed0f23496ffb89681be528d2c0650ecdcf21724d2d7bd9c1ee` |
| Paper | **26.2, build 119** (Java 25+; lobby and every backend) | `a8c9140c3075bd7c04973e9cdc491b21bfe6bad472b674ef932a4ae0fec19629` |

Stale versions must be updated together in every `boot-*.sh` (version, build, SHA-256) — a proxy newer than the backends or mismatched paper-api can break forwarding unexpectedly.

## Layout

```
development-network/
├── SKILL.md
├── network/                    # Gradle plugin (io.github.development-network) — runNetwork task
│   ├── build.gradle.kts        #   java-gradle-plugin + Kotlin 2.4.0 (matches Gradle 9.7.1)
│   └── src/main/kotlin/…       #   DevNetworkPlugin + RunNetworkTask
├── velocity-plugin/            # Velocity proxy plugin — /servers and /plugins
│   ├── build.gradle.kts        #   Java 25 + velocity-api 4.1.1
│   └── src/main/java/…         #   ProxyInspectorPlugin and commands
└── bin/
    ├── dev-network.sh          # boot proxy + lobby + all registered backends
    ├── stop-dev-network.sh     # graceful per-pidfile stop of proxy, lobby, backends
    ├── register-backend.sh     # hot-add a backend to a RUNNING network (no proxy restart)
    ├── unregister-backend.sh   # hot-remove a backend from a RUNNING network (--stop kills it)
    ├── reload-network.sh       # re-apply the registry to the live proxy (idempotent)
    ├── boot-proxy.sh           # download+verify Velocity, generate velocity.toml
    ├── boot-lobby.sh           # download+verify Paper, configure, run lobby (30066)
    ├── boot-backend.sh         # download+verify Paper, configure, run ONE backend
    ├── boot-external.sh        # register+configure an ALREADY-RUNNING server (never starts it)
    ├── fetch-jar.sh            # atomic pinned download (tmp + sha256 + rename, flocked)
    ├── write-ops.sh            # write ops.json (level 4) for DEV_USERS (offline UUIDs)
    └── dev-network-status.sh   # status-ping all endpoints; proves reachability
```

Runtime (default `./development-network`): `logs/` per component, worlds under `runtime/<name>/`, generated configs in `runtime/`, jars cached in `binaries/`. Jars are only downloaded when missing; checksums are always verified.

## Requirements

- Java **25** (Velocity and Paper 26.2 both require it)
- `curl`, `sha256sum`, `python3` (status probe), a POSIX `bash`
- `unzip` (only when installing a downloaded lobby map zip)

## Backend registry

Backends are plain names (`[A-Za-z0-9_-]+`). The registry persists in `runtime/backends.txt`; bootstrap sets it explicitly or falls back to that file.

```bash
# two isolated dev servers named "demo" and "vanilla"
BACKENDS='demo vanilla' ./development-network/bin/dev-network.sh

# default (no BACKENDS set): a single backend named "dev"
./development-network/bin/dev-network.sh
```

Ports: proxy `25565`, lobby `30066`, backends `30067 + index` in the sorted name list (so `demo vanilla` → demo `30067`, vanilla `30068`). Override with `PORT_<NAME>` (e.g. `PORT_DEMO=31001`). `TARGET_SERVER=host.docker.internal` points the proxy at host-run servers from inside a container.

## Port allocation

Ports are allocated in one shared mapping (the same math `boot-backend.sh`/`boot-external.sh` use, so they always agree with the proxy):

1. **Default**: `30067 + sorted-registry-index` — each backend's position in the sorted name list.
2. **Persisted live port wins**: `runtime/<name>.port` keeps a registered backend on its live port across
   reindexes and proxy reloads.
3. **Explicit override next**: `PORT_<NAME>` (e.g. `PORT_DEMO=31001`) is used when no persisted port exists.
4. **Managed autos skip** anything occupied **or already reserved** (external or explicit), scanning upward from
   their default.

So `demo ext` with no overrides → demo `30067`, ext `30068`; if `30068` is taken by an external, a managed auto moves up to the next free port. The proxy, lobby (`30066`), and proxy port (`25565`) are checked up front and fail fast if in use.

## Offline-mode preflight (mandatory)

Complete this preflight before starting any component (`dev-network.sh`, `register-backend.sh`, or `runNetwork`) or connecting a client. “Offline mode” here means Minecraft authentication mode, not merely a local process.

1. **Harness-owned Velocity proxy:** inspect the active/generated `runtime/velocity.toml`. It MUST contain `online-mode = false`. If this workflow owns the proxy and the setting is missing or true, regenerate the config with the harness before startup; do not connect until it is rechecked.
2. **Existing or external Velocity proxy:** inspect its active configuration, not only this repository. It MUST contain `online-mode = false`. This workflow MUST NOT change an external proxy automatically. If its mode is unknown or true, stop and ask the user before changing it or proceeding.
3. **Lobby and every backend:** separately verify the lobby’s and each managed backend’s `server.properties`, plus each external backend’s live configuration, contains `online-mode=false`. The proxy setting does not configure Paper servers. Stop and correct the lobby/backend or ask the user before proceeding when any one is unknown or online.
4. Only after the proxy, lobby, and every backend pass these checks may the network start and a client connect.

## Start

```bash
BACKENDS='demo vanilla' ./development-network/bin/dev-network.sh
```

After the offline-mode preflight passes, this brings up the proxy, lobby, and every registered backend; waits for all ready markers; and prints the connection banner. Connect Minecraft to **`localhost:25565`**.

The controller runs `boot-lobby.sh` under a supervisor loop. If the lobby booter or Paper process exits unexpectedly,
the supervisor clears stale ready/pid markers, waits two seconds, and starts it again. An intentional controller
shutdown disables the restart loop and stops the current lobby child.

## Lobby world (optional, first setup only)

The lobby boots with a generated empty world unless you point it at a world zip. On **first setup only** — when `runtime/lobby/world/level.dat` does NOT exist — it downloads, SHA-256-verifies (`LOBBY_MAP_SHA256`), and unpacks the map into `runtime/lobby/world` before Paper starts. **The install is immutable: once a world exists, later boots never download or extract (URL+SHA256 are ignored), so a live world is never overwritten.** An existing generated world is preserved the same way.

No map URL is hardcoded because every mainstream map host (Planet Minecraft, CurseForge, BuiltByBit, MinecraftMaps) blocks plain `curl` with 403 / ad-shortener walls — the harness downloads with `curl` + a pinned hash, so host the zip somewhere curl can fetch (a GitHub release asset or your own server) and pin it:

```bash
URL=https://example.com/lobby.zip \
LOBBY_MAP_SHA256=<64-hex sha256 of the zip> \
./development-network/bin/dev-network.sh
```

**Source requirements (verify before first use):**
- Prefer a **versioned GitHub release asset** (tagged, immutable) or your own server; record the release's SHA-256 (`curl -fsSL "$URL" | sha256sum`) and the map's license/attribution in your notes so the pin is auditable.
- Map zips must contain `level.dat` at the archive root (or exactly one top-level world folder); the harness refuses a zip without it.
- Keep the map within the harness's target Minecraft data version (Paper 26.2 reads 19133) — a map built for an old release gets upgraded by Paper at first boot.
- To force a re-install (new map version), delete `runtime/lobby/world` (and the old zip cache if present) — only then does a new URL+SHA256 apply.

## Per-backend plugins

Each backend is a fully isolated Paper server (`runtime/<name>/`); plugins load from `runtime/<name>/plugins/`. Install on boot with the per-backend env var (falls back to `PLUGIN_JAR` for single-backend use):

```bash
PLUGIN_DEMO=/path/to/demo/plugin.jar \
PLUGIN_VANILLA=/path/to/other/plugin.jar \
BACKENDS='demo vanilla' ./development-network/bin/dev-network.sh
```

## Proxy Inspector plugin

`velocity-plugin/` is a standalone Velocity 4.1.1 proxy plugin. Build it with Gradle 9.7.1, then copy the
resulting jar into the proxy's `runtime/plugins/` directory before starting the proxy:

```bash
BASE=/path/to/development-network
cd velocity-plugin
gradle build
mkdir -p "$BASE/runtime/plugins"
cp build/libs/proxy-inspector-*.jar "$BASE/runtime/plugins/"
```

It registers:

- `/servers` (alias `/serverlist`) — asynchronously pings every registered server, then reports online/offline
  counts, the names in each group, endpoints, and connected-player count.
- `/plugins` (alias `/pluginlist`) — lists the plugins loaded by the Velocity proxy, including their IDs and
  versions.

The plugin leaves Velocity's built-in `/server <name>` command untouched. These commands inspect the proxy only:
Velocity cannot discover the plugin list of a Paper backend without a separate backend plugin and reporting
protocol. Restart the proxy after installing or updating the jar.

## Runtime registration (hot-add backends, no proxy restart)

A backend can join an **already-running** network — this is the entry point for another agent/process hooking in after the first boot. The proxy keeps running; nothing is restarted.

```bash
# boot a NEW managed server at runtime/hero/ and route it live
REGISTRATION_OWNER=agent-hero \
  ./development-network/bin/register-backend.sh hero "" /path/to/hero/server

# join an ALREADY-RUNNING server (external semantics: never started/stopped)
REGISTRATION_OWNER=agent-hero \
  ./development-network/bin/register-backend.sh hero 30070
```

`REGISTRATION_OWNER` is persisted in `runtime/hero.owner`; use the same token for
unregistration. The managed form boots the supplied `SERVER_DIR`; the external form only verifies the live
server and never starts or stops it.

What it does: picks a free port (scanning up from 30067, or exact/`PORT_<NAME>` port), atomically claims the
unique name and port, appends the name to `runtime/backends.txt`, optionally boots the server dir (waits for
ready) or marks an external one ready, regenerates `velocity.toml` with the **same generator the proxy used at
boot** (`bin/velocity-toml.sh` — identical config, so a reload is a no-op diff), then sends `velocity reload`
through the proxy's command FIFO (`runtime/velocity.cmd`). Velocity re-reads the config live: `[servers]`+`try`
gain the new entry and `/server <name>` routes to it — proven by `Velocity configuration successfully reloaded.`
in the proxy log. The running proxy's bound port is read back from its `velocity.toml` so a non-default
`PROXY_PORT` is preserved.

### Ownership contract

A `BASE` directory is one network coordination domain. It has exactly one infrastructure owner and zero or more
backend owners:

- `runProxy` (or the shell `dev-network.sh` controller) is the only task/process allowed to start or stop the
  proxy and lobby. It owns the proxy runtime state.
- `registerBackend` owns exactly one external backend registration. It never starts or stops Paper, the proxy, or
  the lobby.
- `runBackend` owns exactly one managed backend registration and its Paper process. It never starts, restarts, or
  stops the proxy or lobby.
- `runtime/register.lock` serializes registry, port, ownership metadata, config regeneration, and reload.
  Duplicate backend names fail instead of replacing another owner's registration.
- `unregister-backend.sh` may remove only the registration owned by the caller; an explicit force operation is
  reserved for the network owner.

### Component ownership matrix

| Component | Starts/stops | Registry/config writes | Live reload |
|---|---|---|---|
| `runProxy` / `dev-network.sh` with `NETWORK_ROLE=proxy` / `boot-proxy.sh` | Shared proxy and lobby only | Initial proxy config and persisted registry ports | No |
| `runNetwork` / `dev-network.sh` with `NETWORK_ROLE=full` | Proxy, lobby, and its one-project backend | Initial full-stack config | No |
| `registerBackend` / `register-backend.sh` | Nothing; the external Paper process belongs to its caller | Its name, port, owner metadata, and regenerated config | Yes |
| `runBackend` / `register-backend.sh` | Its managed Paper backend only | Its name, port, owner metadata, and regenerated config | Yes |
| `unregisterBackend` / `unregister-backend.sh` | Nothing by default; optional stop is managed-only | Removes its registration and metadata | Yes when the proxy is live |
| `reload-network.sh` | Nothing | Regenerates config from the registry | Yes |

Only the first two rows can own proxy lifecycle. Registration is a serialized set of registry/config mutations
plus a reload request, not a filesystem transaction.

### Agent split (recommended)

Start the infrastructure once:

```bash
./gradlew runProxy
```

Then each plugin/server agent chooses one backend mode:

```bash
# Managed mode: this task starts/stops Paper for this project.
./gradlew runBackend

# External mode: runServer (or another process) already owns Paper.
./gradlew registerBackend \
  -PnetworkBackend=hero \
  -PnetworkBackendPort=30070 \
  -PnetworkRegistrationOwner=agent-hero
```

`runBackend` owns the managed Paper process and unregisters it on exit. `registerBackend` only verifies and
registers the already-running external server; it returns after registration and never starts or stops Paper.
Neither task can claim the shared proxy. `runNetwork` remains a one-project convenience composite; use the
explicit `runProxy` plus one backend task per plugin project whenever multiple projects share a `BASE`.

To leave a manually registered backend:

```bash
REGISTRATION_OWNER=agent-hero \
  ./development-network/bin/unregister-backend.sh hero
REGISTRATION_OWNER=agent-hero \
  ./development-network/bin/unregister-backend.sh hero --stop
./development-network/bin/unregister-backend.sh hero --force  # network-owner cleanup
./development-network/bin/reload-network.sh                   # re-apply registry (idempotent)
```

Port safety: every resolved port is persisted in `runtime/<name>.port` so a runtime reindex never moves a live
server. Concurrent registrations are serialized by `runtime/register.lock`; a second owner cannot claim an
existing backend name or port.

## Gradle integration: `runProxy`, `runBackend`, and `registerBackend`

The harness ships as a Gradle plugin (`io.github.development-network`, module `network/`). It exposes separate
tasks for shared infrastructure, managed Paper, and external registrations:

- `runProxy` starts the shared proxy and lobby and holds the infrastructure ownership lease.
- `runBackend` builds this project's jar, starts its managed Paper backend, registers it, and owns that backend
  until the task exits.
- `registerBackend` attaches an already-running external Paper server. It does not build a jar or start/stop
  Paper.
- `unregisterBackend` removes this project's external registration without stopping its Paper process.
- `runNetwork` remains the one-project convenience task that starts the full stack. It is not the multi-agent
  coordination primitive.

For a shared network, run `runProxy` once from the network/controller project, then choose `runBackend` or
`registerBackend` from each plugin project. A plugin project running either backend task cannot claim the proxy.

### Standalone `plugin-multiplexer`

Clone or extract this repo (`aincraft-org/plugin-multiplexer`) and include the `network/` build:

```kotlin
// settings.gradle.kts
includeBuild("/path/to/plugin-multiplexer/network")

// build.gradle.kts — inside the EXISTING plugins block (a second plugins block is illegal)
plugins {
    id("io.github.development-network")
}
```

When the plugin is used through a composite build (`includeBuild`), the Gradle tasks auto-discover the harness
`bin/` directory from the included build. If the repo is not included as a composite build, set the harness path
explicitly:

```bash
export DEV_NETWORK_BIN=/path/to/plugin-multiplexer/bin
# or
export DEV_NETWORK_DIR=/path/to/plugin-multiplexer
```

### Vendored as a `server-development-skills` submodule

`server-development-skills` vendors this harness as a Git submodule at `development-network/`. Use the submodule
path exactly as before:

```kotlin
includeBuild("./development-network/network")
```

```bash
./gradlew runProxy
# -PnetworkBase=<dir>       shared network runtime dir (default: run/network)
# -PnetworkProxyPort=<n>    proxy port (default: 25565; 0 = auto-pick free port)

./gradlew runBackend
# -PnetworkBackend=<name>   backend name (default: project.name)
# -PnetworkJarTask=<name>   Jar task to deploy (default: "jar")
# -PnetworkDevUsers=<name>  accounts to op on the managed backend (default:
#                           $DEV_NETWORK_DEV_USERS env, else "dev")

./gradlew registerBackend
# -PnetworkBackend=<name>           external backend name (default: project.name)
# -PnetworkBackendPort=<port>       port already used by Paper
# -PnetworkRegistrationOwner=<id>   stable owner token for register/unregister

./gradlew unregisterBackend
# uses networkBackend and networkRegistrationOwner
```

`runBackend` builds the jar using its actual `archiveFile`, copies it into the isolated backend, asks
`register-backend.sh` to start/register that backend, and blocks like `runServer`; Ctrl-C unregisters and stops
only that managed backend. `registerBackend` requires an already-running Paper server and returns after verifying
and registering it; it never builds, starts, or stops Paper. `unregisterBackend` removes only that external
registration. `runProxy` owns the proxy/lobby process and remains up until its task is stopped. The Kotlin pin
is **2.4.0** (Gradle 9.7.1 bundles 2.4.0; older Kotlin fails the applied-script/Kotlin-module checks), and
task classes must stay `abstract` (Gradle requirement).


## Repository structure

This harness lives in its own repository: **`aincraft-org/plugin-multiplexer`**.
`server-development-skills` consumes it as a Git submodule at `development-network/`, pinned to a release tag. The module layout is:

```
.
├── SKILL.md
├── network/                    # Gradle plugin (io.github.development-network)
│   ├── build.gradle.kts
│   └── src/main/kotlin/…
├── velocity-plugin/            # Velocity proxy plugin
│   ├── build.gradle.kts
│   └── src/main/java/…
└── bin/                        # shell harness (boot, stop, status, register, ...)
```

To update the submodule pin: `git submodule update --init -- development-network` in `server-development-skills`, or check out a newer tag in the `plugin-multiplexer` repo and update the gitlink.

## Permissions & console admin

**Backend servers opp the developer automatically.** Every boot writes `ops.json` (operator level 4) for the accounts in `DEV_USERS` (space-separated, default `dev`):

```bash
DEV_USERS='dev jlo' BACKENDS='demo vanilla' ./development-network/bin/dev-network.sh
```

Each backend is OFFLINE mode, so ops use the **name-derived offline UUID** — the exact `java.util.UUID.nameUUIDFromBytes("OfflinePlayer:"+name)` algorithm — computed by `bin/write-ops.sh` (verified byte-for-byte against Java). Log in with any username from `DEV_USERS` and you are opped on managed backends.

**Velocity has no OP state.** It uses permission nodes, while Paper backends use `ops.json`. Backend `*`/op does
not carry to the proxy.

When the Proxy Inspector jar is installed on the proxy, every username in `DEV_USERS` receives these explicit
Velocity nodes: `velocity.command.*`, `velocity.command.info`, `velocity.command.plugins`,
`velocity.command.reload`, `velocity.command.dump`, `velocity.command.heap`, `velocity.command.glist`, and
`velocity.command.send`. `velocity.command.*` is included because Velocity's built-in admin command uses that
node; unlisted players retain Velocity's default permissions. `runProxy`/`dev-network.sh` passes `DEV_USERS` to
the proxy, and managed backends receive the same users through `write-ops.sh`:

```bash
DEV_USERS='your-minecraft-name' ./development-network/bin/dev-network.sh
```

This development-only grant must not be installed on a shared or production proxy; use a real proxy permissions
plugin there instead.

External Paper servers remain fully external: `registerBackend` never edits their files or grants backend ops.
Configure `/op your-minecraft-name` (or the external server's permission plugin) there separately.

Without Proxy Inspector, open proxy admin commands with a proxy permissions plugin (for example LuckPerms; its
proxy command is `/lpv`). The relevant nodes include `velocity.command.*`, `velocity.command.glist`, and
`velocity.command.send`.

## Iterating: rebuild → restart ONE backend

```bash
# in the plugin project
./gradlew build
# deploy + restart just "demo" (lobby, proxy, other backends stay up)
./development-network/bin/restart-backend.sh demo /path/to/demo/plugin.jar
```

## Drop-in backends (fully managed, zero env vars)

The proxy itself is only a router — **it cannot start or stop servers**. The harness manages servers; drop-in mode makes that fully automatic. Put each plugin's server folder (with the built jar in its `plugins/`) under `runtime/auto/<name>/`:

```text
development-network/runtime/auto/
├── myplugin/plugins/myplugin.jar
└── other/plugins/other-<calver>.jar
```

Then just boot:

```bash
./development-network/bin/dev-network.sh
```

The harness discovers every `runtime/auto/*/` folder, generates its full config (Velocity modern-forwarding secret, `online-mode=false`, ops via `write-ops.sh`), picks a free port, registers it in `velocity.toml` `[servers]`/`try`, boots it, opps your `DEV_USERS`, and manages it (pidfile, restart via `restart-backend.sh <name> <jar>`, stop). No `BACKENDS`/`PLUGIN_*` env vars needed. When auto dirs exist they replace the default `dev` backend.

## Bring your own server (join an external server)

Don't want the harness to run your server? Join an **already-running** Paper server (e.g. your plugin's own `./gradlew runServer` launched in the plugin project) to the network as a backend. **The harness never modifies the external server's files and never starts/stops it** — it only registers it and verifies the forwarding config.

One-time external-server setup (in the plugin project's server dir):

```yaml
# config/paper-global.yml (keep the rest of the file; Paper merges)
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "dev-local-forwarding-secret-change-me"
```

plus `online-mode=false` in `server.properties`, then **restart the external server once**. After that, join it:

```bash
EXTERNAL_DIR_NAMEPLUG=/path/to/plugin-project/run \
BACKENDS='dev' EXTERNAL_BACKENDS='nameplug' \
./development-network/bin/dev-network.sh
```

- `EXTERNAL_BACKENDS` names are merged into the registry, so the proxy's `[servers]` includes them (and port math covers them — `PORT_<NAME>` overrides still work).
- `boot-external.sh` verifies the forwarding config is present (prints the exact block + path if missing) and confirms the server is reachable; it writes only the `runtime/<name>.ready` marker.
- External servers are **not** auto-opped (`write-ops.sh` only runs for managed backends); op yourself with `/op <name>` or your normal plugin flow.
- The stop script never stops external servers — no pidfile means it leaves them alone; you stop `runServer` in the plugin project as usual.

## Verifying reachability

```bash
./development-network/bin/dev-network-status.sh
```

Status-pings the proxy and every endpoint with a correct protocol handshake:

```
proxy                  reachable  motd='dev-network' version=Velocity 1.7.2-26.2
lobby                  reachable  motd='dev-network lobby' version=Paper 26.2
backend:30067 (30067)  reachable  motd='dev-network demo' version=Paper 26.2
backend:30068 (30068)  reachable  motd='dev-network vanilla' version=Paper 26.2
```

**Labeling caveat:** the proxy replies with its OWN status (its `motd`; `ping-passthrough = "DISABLED"`), and the backends are probed directly. So the probe proves **reachability**, not routing. Routing/multiplexing is proven by a real login: join `localhost:25565`, land on lobby, `/server demo`, `/server vanilla`.

## Architecture notes

- **Velocity modern forwarding**: `player-info-forwarding-mode = "modern"` in `velocity.toml`, with proxy `online-mode=false` by default. Set `PROXY_ONLINE_MODE=true` (or Gradle `-PnetworkOnlineMode=true`) to require Mojang/Microsoft authentication at the proxy; backends remain `online-mode=false` with the shared secret in `runtime/forwarding.secret` mirrored into each backend's `config/paper-global.yml` → `proxies.velocity.secret`. Offline mode keeps dev accounts (and a Rust Azalea bot from `autonomous-testing`) connectable without Mojang auth.
- **Every backend** sets `server.properties` `online-mode=false` and `spigot.yml` `settings.bungeecord: false` (modern forwarding REQUIRES BungeeCord forwarding off).
- `forwarding.secret` is generated per boot with a fixed dev secret string — a dev secret, never a production credential.
- The `try` list for login/kick failover is `["lobby", <backends…>]` — lobby first.
- Configs are generated on every boot; worlds persist in the backend's runtime dir.
- Jar downloads are atomic and race-safe: temp file in the same dir, SHA-256 verify, `mv` into place, per-jar `flock` — a fresh multi-backend boot cannot corrupt a jar that another booter is still writing.
- Teardown: `Ctrl-C` on the launcher SIGINTs all booters (their EXIT traps stop java), or `stop-dev-network.sh` SIGTERMs each Java PID by pidfile — so Paper's world-save hooks always run. Never `pkill` patterns.

## Stopping

```bash
./development-network/bin/stop-dev-network.sh
```

Stops proxy, lobby, and every registered backend by pidfile (Java PID, so Paper's save hooks run), escalates to SIGKILL after 30s, clears ready markers.

## Common mistakes (observed)

| Wrong | Right | Why |
|---|---|---|
| Running the network without Java 25 | Install Java 25 first | Velocity 4.1.1 and Paper 26.2 both require Java 25+; older JDKs fail to start |
| Waiting for ready markers before the servers finish booting | Ready markers are written AFTER the port opens; poll the port, not the marker, for instant checks | A marker can exist while java is still booting (or be stale from a previous run) |
| Using `settings.bungeecord: true` on backends with modern forwarding | Keep `spigot.yml` `settings.bungeecord: false` | Modern forwarding requires BungeeCord forwarding OFF; legacy mode is less secure |
| Updating only one `boot-*.sh` version pin | Update version+build+SHA-256 in every `boot-*.sh` | Stale backends/proxy drift breaks forwarding |
| Killing the booter shell expecting the network to die | Use `stop-dev-network.sh` (pidfiles point at the Java PIDs) | `Ctrl-C` only kills that booter; java keeps running and rebinding ports |
| Leaving stale CalVer plugin jars in `plugins/` | `restart-backend.sh` clears `*.jar` before installing | Old versions with a newer CalVer stay loaded; both get enabled at boot |
| Naming a backend with spaces/special chars | `[A-Za-z0-9_-]+` only | Names become server names in velocity.toml and runtime dirs |
| Expecting the status probe to prove routing | Read it as reachability; prove routing with a real login + `/server` | The proxy answers pings itself (`ping-passthrough = DISABLED`) |
| Logging in without ops and expecting console admin | Set `DEV_USERS` to your account before boot | Ops come from `ops.json` (offline UUIDs) written per boot |
| Computing offline UUIDs with `uuid3(nil, ...)` | Use `java.util.UUID.nameUUIDFromBytes("OfflinePlayer:"+name)` (raw md5) | The nil-namespace prefix yields a wrong UUID that never matches the player |
| Expecting `*`/op on a backend to grant proxy commands | Install a proxy permissions plugin (e.g. LuckPerms, `/lpv`) and grant `velocity.command.*` | Backend and proxy permission systems are fully separate |
| Using port 25565 on a backend | Backends bind 30067+; only the proxy owns 25565 | Only the proxy is reachable by the client |
| Expecting `/server` to work on a backend directly | `/server` is a Velocity built-in; it only exists on the proxy | Backends have no proxy command routing |
| Modifying a running server's config and expecting it to apply | Restart that component (or full `stop-dev-network.sh` + boot) | Velocity/Paper read configs at startup only |
| Starting or connecting before the offline-mode preflight | Verify the active Velocity config and every backend has `online-mode=false`; set an owned proxy to offline mode, or stop and ask before changing or proceeding with an external proxy | Backend offline settings do not prove the active proxy is offline, and an unknown external proxy mode is not permission to proceed |
| Setting `URL` without `LOBBY_MAP_SHA256` (or vice versa) | Set both, or neither | The world install requires a pinned hash; partial config fails fast at boot with an explicit error |
| Pointing `URL` at Planet Minecraft / CurseForge / BuiltByBit / MinecraftMaps | Host the zip where plain `curl` works (GitHub release asset, your own HTTP server) | Those hosts 403 plain curl or need browser ad-shortener clicks |
| Expecting a URL+SHA256 change to replace an installed world | Delete `runtime/lobby/world` first (and the old `.world.zip` cache) | The map install is immutable: an existing `world/level.dat` always wins, never overwritten |
| A stale `.port` file from a previous run pinning a dead port | Remove `runtime/<name>.port` before re-registering that name | The generator trusts the persisted port, so a stale file points the proxy at a dead listener |
| Two agents registering the same name concurrently | Names must be unique per `backends.txt`; the flock serializes the registry but a duplicate name is a write race | `[servers]` ends up with one entry for two different ports |