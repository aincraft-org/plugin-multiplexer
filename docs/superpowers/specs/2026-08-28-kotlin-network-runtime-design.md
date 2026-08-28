# Kotlin Development-Network Runtime

## Status

Approved design direction. Implementation must follow this specification after the implementation plan is approved.

## Goal

Replace the development-network shell implementation with centralized Kotlin runtime code and expose the supported network operations through Gradle tasks in consuming projects. Gradle tasks are the only supported public entry point after the migration. The repository root remains a quality wrapper; it does not apply the network plugin or become a runtime project.

The migration preserves the existing one-connection developer workflow:

```text
Minecraft client -> localhost:25565 -> Velocity -> lobby or named Paper backend
```

It also preserves the shared-network ownership model: one infrastructure owner, independently owned managed backends, and metadata-only external registrations.

## Decisions

- Remove the `bin/*.sh` command-line interface rather than retain compatibility launchers.
- Keep the five existing task names: `runProxy`, `registerBackend`, `unregisterBackend`, `runBackend`, and `runNetwork`.
- Add four lifecycle/diagnostic tasks: `stopNetwork`, `reloadNetwork`, `restartBackend`, and `networkStatus`.
- Keep runtime tasks in projects that apply `io.github.development-network`; do not apply that plugin from the repository root.
- Package a separate Kotlin runtime JVM as a fat JAR and embed it in the Gradle plugin artifact. Consumer builds do not need source-tree paths, `DEV_NETWORK_BIN`, `DEV_NETWORK_DIR`, generated shell launchers, or a separately installed runtime.
- Launch the runtime with the Gradle JVM's `${java.home}/bin/java`. The runtime and plugin target compatible JVM bytecode; the selected Java runtime must satisfy the documented Java 25 requirement for Velocity and Paper.
- Use direct JDK APIs. Do not invoke a shell for lifecycle, configuration, downloads, extraction, process control, or status probes.
- Treat `SKILL.md` ownership, port, preflight, forwarding, locking, and safety behavior as the compatibility contract.

## Scope

### Former shell responsibilities to migrate

Every current script is covered by Kotlin code or by a Gradle task:

| Former script | Kotlin owner | Public task or use case |
|---|---|---|
| `dev-network.sh` | infrastructure controller and runtime command dispatcher | `runProxy`, `runNetwork` |
| `stop-dev-network.sh` | owner-verified stop service | `stopNetwork` |
| `register-backend.sh` | serialized registration service | `registerBackend`, `runBackend` |
| `unregister-backend.sh` | owner-verified unregistration service | `unregisterBackend`, managed cleanup |
| `reload-network.sh` | deterministic regeneration and controller reload service | `reloadNetwork` |
| `restart-backend.sh` | managed-backend restart service | `restartBackend` |
| `boot-proxy.sh` | proxy launcher inside infrastructure controller | internal |
| `boot-lobby.sh` | lobby launcher and supervisor | internal |
| `boot-backend.sh` | managed backend launcher | internal |
| `boot-external.sh` | external verification service | internal registration path |
| `velocity-toml.sh` | deterministic `VelocityConfigWriter` | boot/reload |
| `fetch-jar.sh` | pinned `ArtifactFetcher` | proxy/lobby/backend boot |
| `install-lobby-map.sh` | `LobbyMapInstaller` | lobby boot |
| `write-ops.sh` | `OpsWriter` | managed Paper boot |
| `dev-network-status.sh` | Minecraft status protocol client | `networkStatus` |
| `test-network.sh` | Kotlin/runtime integration tests | test suite |
| `test-lobby-map.sh` | archive installer tests | test suite |

No shell script remains as a supported interface. Internal Kotlin use cases replace the scripts; they are not exposed as one task per helper.

### Out of scope

- Changing Velocity or Paper versions, forwarding protocol, or pinned checksums.
- Changing the proxy-inspector plugin.
- Adding client-side server/address tracking.
- Making external Paper processes managed by this repository.
- Applying the network plugin to the root quality-wrapper project.
- Introducing a production credential or production permissions mechanism.
- Replacing the documented runtime state with a database.

## Public Gradle API

The plugin registers nine abstract `DefaultTask` types in the `network` group. Long-lived tasks remain blocking tasks; short operations return after their operation completes.

| Task | Behavior | Ownership |
|---|---|---|
| `runProxy` | Start and own the shared proxy and lobby; block until stopped | proxy/lobby only |
| `runBackend` | Build/deploy this project's JAR, register one managed backend, start it, and block; cleanup on exit | one managed backend |
| `registerBackend` | Attach and verify an already-running external Paper server; return after registration | external registration metadata only |
| `unregisterBackend` | Remove this project's external registration; never stop Paper | external registration metadata only |
| `runNetwork` | One-project convenience mode: own proxy, lobby, and this project's managed backend; block until stopped | full one-project stack |
| `stopNetwork` | Ask the addressed controller to stop, then perform owner-verified fallback cleanup | controller-owned components only |
| `reloadNetwork` | Regenerate deterministic proxy configuration from the registry and request a live proxy reload | registry/config/reload operation |
| `restartBackend` | Stop and restart one managed backend, replacing only its plugin JAR | selected managed backend |
| `networkStatus` | Probe proxy, lobby, and registered backend endpoints and print reachability/player/version data | read-only |

Existing `-P` property names remain supported where applicable:

- `networkBase` — default `run/network`.
- `networkBackend` — default `project.name`.
- `networkBackendPort` — required for `registerBackend`; range `1024..65535`.
- `networkProxyPort` — default `25565`; `0` chooses a free port.
- `networkJarTask` — default `jar`.
- `networkDevUsers` — fallback `DEV_NETWORK_DEV_USERS`, then `dev`.
- `networkOnlineMode` — optional `true` or `false`, mapped to proxy online mode.
- `networkRegistrationOwner` — stable external owner token; otherwise generated from project path and backend name.
- `networkTargetServer` — optional target host, default `localhost`.
- lobby map properties mirror the existing environment names through Gradle properties where needed: `networkLobbyMapUrl`, `networkLobbyMapSha256`, and `networkLobbyMapRandomUrl`.

Task inputs are provider-backed where Gradle supports them. Long-lived external side effects are marked non-cacheable. Managed tasks depend on the configured `Jar` task and deploy its actual `archiveFile`, deleting stale JARs from the selected isolated backend plugin directory before copying the fresh artifact.

## Runtime packaging and launch

The `network` Gradle build gains an internal `runtime` subproject. It contains no Gradle API dependency and exposes a Kotlin `main` entry point. Its `runtimeJar` is a self-contained JAR containing runtime classes and required Kotlin runtime dependencies. The runtime uses only JDK APIs beyond Kotlin stdlib.

The plugin JAR embeds the runtime JAR at:

```text
META-INF/development-network/runtime.jar
```

The plugin's launcher performs these steps:

1. Open the embedded resource from the plugin classloader.
2. Compute its SHA-256 and derive a content-addressed cache directory below the consumer Gradle user home.
3. Acquire an extraction lock in that cache directory.
4. Reuse an existing file only when its size and SHA-256 match.
5. Stream the resource to a same-directory temporary file, verify its checksum, and atomically move it into place.
6. Launch `${java.home}/bin/java -jar <cached-runtime.jar> ...` with typed command arguments and the resolved runtime settings.

A failed extraction deletes only its own temporary file. It never overwrites a verified cached runtime. Composite-build and published-plugin consumers therefore execute the same embedded runtime artifact. There is no fallback to a source-tree `bin` directory.

The runtime command line is an internal stable protocol between the plugin and embedded artifact. It carries explicit values rather than relying on inherited `BACKENDS`, `EXTERNAL_BACKENDS`, or shell environment state. Environment variables are read only at Gradle configuration time for documented fallbacks.

## Runtime layers

The runtime is organized so that shared rules have one implementation:

```text
runtime/
├── Main.kt                         # command parsing and exit-code mapping
├── model/                          # validated names, owners, roles, requests
├── state/                          # RuntimeLayout, atomic files, leases, locks
├── registry/                       # registry transitions and port allocation
├── config/                         # Velocity/Paper config and ops writers
├── artifact/                       # pinned downloads and lobby map installer
├── process/                        # process identity, readiness, supervision
├── controller/                     # proxy/full/backend controllers and control socket
└── status/                         # Minecraft status protocol client
```

The Gradle plugin contains only task adapters, provider/property resolution, runtime extraction, and child-process waiting. It does not duplicate registry, lifecycle, config, or safety logic.

## State and ownership

The existing runtime names remain the compatibility boundary:

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

The controller lease records a unique run token, role, controller PID, controller process start identity, child PID/start identities, bound proxy port, and timestamps. Numeric PID values alone are never sufficient for signaling. Before any signal or cleanup, the runtime verifies process start identity plus the expected executable/work directory where available. Stale records are diagnosed and removed only when they cannot identify a live owner.

`proxy.lock` is held by the infrastructure controller for its full lifetime. It prevents a second proxy/full controller from claiming the shared infrastructure. `register.lock` serializes registry changes, port claims, ownership metadata, deterministic config generation, and the live reload request. Per-artifact locks serialize pinned JAR fetches.

Registration is a serialized state transition, not a filesystem transaction. The service must roll back only state created by its own run token when managed startup or final commit fails. A concurrent owner cannot replace a name or port between registration, boot, and commit.

External registrations carry an explicit external ownership mode and no managed process identity. Every stop, restart, rollback, and cleanup path checks that mode before touching a process or backend directory. External processes and files remain untouched.

## Registry and ports

The registry rules are centralized and deterministic:

1. Resolve explicit `BACKENDS`-equivalent task input, persisted `runtime/backends.txt`, auto backend directories, and the documented default according to the task mode.
2. Validate every backend against `[A-Za-z0-9_-]+`.
3. Sort and deduplicate names before persisting one name per line.
4. Merge explicitly registered external names only where the full controller contract requires it.
5. Resolve each port in this order: persisted `<name>.port`, explicit `PORT_<NAME>`-equivalent value, sorted-index default `30067 + index`.
6. Reject proxy/lobby collisions, duplicate claims, invalid ports, and a live external port that is not actually listening.
7. Managed automatic allocation scans upward from its default and skips occupied sockets and all ports already reserved in the current registry transition.
8. Persist each chosen port atomically before starting its owner.

The proxy port defaults to `25565` and may use a free-port scan only when `networkProxyPort=0`. The lobby remains `30066`. Persisted backend ports remain authoritative across registry reindexing and reloads.

## Controller and process lifecycle

### Proxy/full controller

`runProxy` and `runNetwork` launch the runtime in controller mode. The controller:

1. Acquires `proxy.lock` and validates the existing proxy lease.
2. Resolves and persists the registry and ports.
3. Runs offline-mode and forwarding preflight before starting any component.
4. Fetches the pinned Velocity and Paper artifacts with checksum verification.
5. Writes deterministic Velocity and Paper configuration.
6. Creates the controller control socket and authentication token.
7. Starts the proxy with a retained input channel owned by the controller.
8. Starts the lobby supervisor. An unexpected lobby exit clears stale markers, waits two seconds, and restarts it; intentional shutdown disables that restart loop.
9. In full mode only, starts the controller-owned managed backend. External backends are verified but never started.
10. Waits for readiness based on the bound socket, then writes ready markers and prints the connection banner.
11. Serves authenticated `reload` and `shutdown` control requests while waiting.
12. On intentional shutdown or interruption, stops only its owned child processes, clears owner-matching state, releases locks, and exits.

The controller's proxy stdin channel replaces the shell FIFO. The control socket is used for cross-invocation reload/shutdown requests; only a request carrying the current token and matching the persisted controller lease is accepted. A stale socket or token fails closed.

### Managed backend controller

`runBackend` launches a runtime command that owns one managed backend. It deploys the current JAR, creates or updates the isolated backend configuration, registers the backend under a unique run owner, starts Paper, waits for readiness, and blocks until Paper exits or the task is interrupted. It never starts, restarts, or stops the proxy or lobby.

On normal task exit, cancellation, or startup failure, it unregisters and stops only its owner-matching managed process. Termination is graceful first, with the documented timeout and force escalation. Cleanup failures are reported and never broadened to another backend.

### External registration

`registerBackend` requires a name, explicit port, stable owner, live proxy controller, and already-running Paper server. It verifies:

- the server is reachable on the requested port;
- `server.properties` has `online-mode=false`;
- `config/paper-global.yml` enables Velocity modern forwarding, has `online-mode=false`, and contains the shared forwarding secret.

If configuration is missing or unknown, registration fails with the exact path and required configuration; it never writes the external server. It records only registry/name/port/owner/ready metadata, regenerates the proxy configuration, and sends an authenticated reload request.

`unregisterBackend` removes only the matching external owner registration. It does not stop Paper, remove external files, deploy JARs, or generate ops. Managed cleanup is owned by `runBackend` or `restartBackend`.

### Stop and restart

`stopNetwork` first requests authenticated controller shutdown and waits for the lease to disappear. If the controller is unavailable, it performs owner/start-identity-verified fallback cleanup only for infrastructure and managed processes explicitly owned by the addressed run. It never uses process-name matching, `pkill`, or an unverified PID. It does not stop another project's managed backend merely because that backend appears in the registry.

`restartBackend` targets one managed registration, stops the matching process with the backend restart timeout, clears its ready marker, replaces only that backend's plugin JAR, and starts it again using the same persisted port. Proxy, lobby, external backends, and other managed backends remain up.

## Configuration and forwarding

`VelocityConfigWriter` is the one deterministic generator used for initial boot and reload. It preserves the current pinned Velocity configuration, modern forwarding mode, target host, proxy online-mode choice, lobby-first `try` list, sorted backend entries, and persisted ports. It writes `runtime/velocity.toml` atomically and updates the fixed development forwarding secret at `runtime/forwarding.secret`.

Paper configuration writers generate `server.properties` with the selected port and `online-mode=false`, modern forwarding settings with the shared secret, EULA acceptance, and `spigot.yml` `settings.bungeecord: false`. Managed servers receive `ops.json` using the exact Java `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` algorithm at operator level 4. External servers never receive generated configuration or ops.

Before startup or client connection, preflight checks the active/generated proxy, lobby, and every backend independently. An owned proxy may be regenerated to offline mode. An external proxy or external backend with unknown/online mode causes a failure with an actionable request; the runtime must not silently change it.

## Artifact and archive safety

`ArtifactFetcher` preserves the current pinned download contract:

- per-destination blocking file lock;
- existing-file checksum verification before reuse;
- stale destination removal after a failed verification;
- same-directory temporary download;
- streaming SHA-256 computation while downloading;
- exact expected checksum comparison;
- atomic rename only after verification;
- cleanup of failed temporary files.

`LobbyMapInstaller` preserves static and random modes:

- static mode requires both URL and exact SHA-256;
- random mode requires only the random URL and is mutually exclusive with static variables;
- no map mode keeps generated empty-world behavior;
- an existing `runtime/lobby/world/level.dat` makes installation immutable;
- dynamic downloads are checksum-verified locally before extraction and are not retained as a reusable random selection cache after successful installation.

ZIP validation occurs before extraction and rejects:

- absolute paths;
- `..` traversal components;
- backslashes and Windows drive-style paths;
- duplicate entries;
- symlink entries;
- device, FIFO, socket, and other special file types;
- malformed archives;
- archives without `level.dat` at the root or exactly one top-level world directory;
- archives that would escape the temporary extraction directory.

Validation must inspect ZIP entry metadata, including external attributes where needed; a broad unchecked `ZipInputStream` extraction is not acceptable. Extraction occurs into a temporary sibling directory and is installed atomically only after full validation and successful extraction.

## Status protocol

`networkStatus` reads the persisted registry and ports, probes the proxy/lobby/backend endpoints with the existing Minecraft handshake/status protocol, and prints one report per endpoint. It returns a nonzero result when a required endpoint probe fails. The report continues to distinguish reachability from routing: proxy status is the proxy's own response, and real login plus `/server <name>` remains the routing proof.

## Gradle task adapter behavior

Task actions create immutable runtime requests from the extension/providers, extract the embedded runtime, and launch it with direct `ProcessBuilder`. They inherit normal console I/O for useful server logs but remove unrelated registry environment variables from proxy/register/unregister requests. They wait interruptibly for long-lived commands and restore the interrupt flag after requesting cleanup.

The adapter must not install JVM shutdown hooks that assume the Gradle daemon is the network owner. Normal task `finally` cleanup is primary; persisted controller identity and the stop/recovery command are authoritative after abrupt daemon termination.

The plugin retains automatic discovery of the configured `Jar` task but removes all shell harness path discovery. Missing or invalid properties fail before launching the runtime. Runtime exit codes map to `GradleException` with the operation and captured diagnostic context.

## Testing strategy

### Runtime unit tests

- backend-name and owner-token validation;
- registry discovery, sort/deduplication, and persisted state precedence;
- occupied/reserved/default/explicit/persisted port allocation;
- duplicate names, duplicate ports, and ownership transitions;
- deterministic Velocity and Paper configuration output;
- online-mode and forwarding preflight decisions;
- offline UUID and ops JSON generation;
- artifact checksum comparison and atomic replacement behavior;
- static/random map-mode validation;
- ZIP rejection for every unsafe path/file-type case and malformed world layout;
- immutable existing-world behavior;
- Minecraft status packet parsing and failure reporting.

### Runtime integration tests

Use temporary directories and fixture Java processes, not real Minecraft downloads:

- proxy lease exclusivity and stale-lease diagnostics;
- serialized concurrent registration and rollback;
- owner/start-identity verification and PID-reuse refusal;
- graceful termination followed by forced escalation;
- lobby restart after unexpected exit and no restart after intentional shutdown;
- external registration surviving unregister, stop, restart, and failed managed cleanup;
- control-socket authentication, reload, shutdown, stale token, and unavailable-controller fallback;
- extraction cache locking and checksum reuse.

### Gradle TestKit

Create an actual temporary consuming project whose `settings.gradle.kts` uses `includeBuild(<worktree>/network)` and whose build applies `io.github.development-network`. Verify:

- all nine tasks and descriptions;
- existing property defaults and validation;
- managed tasks depend on the actual `Jar.archiveFile`;
- registration does not build or wait for a Paper PID;
- long-lived tasks block and accept controlled interruption;
- runtime resource extraction works from the composite plugin classpath;
- root project remains a base-only quality wrapper.

No shell test or shell lint target remains after the removal. The network build must test its runtime subproject and plugin packaging; the root quality wrapper must continue to run nested quality and metadata checks.

## Documentation and CI migration

Update `README.md` and `SKILL.md` to:

- remove the `bin/` tree and direct shell command examples;
- document the nine Gradle tasks and their properties;
- retain the shared-network ownership matrix and agent split;
- explain that the runtime is supplied by the plugin through composite or published consumption;
- preserve offline-mode, forwarding, port, registry, external-server, map, and status caveats;
- update map examples to invoke the appropriate Gradle task.

Update CI and quality instructions to remove `bash -n`, ShellCheck, and shell test harness steps. Add runtime/plugin tests and a real composite-consumer smoke test. Do not change unrelated release artifact or proxy-inspector checks.

## Acceptance criteria

1. No supported lifecycle behavior depends on `bin/*.sh` or shell execution.
2. The nine Gradle tasks work from an actual consuming project using `includeBuild(.../network)`.
3. The root build remains a quality-only wrapper and does not apply the network plugin.
4. Proxy, lobby, managed backend, and external registration ownership boundaries remain enforced.
5. Persisted registry ports, duplicate/occupied port handling, locks, owner records, readiness, reload, stop, restart, and stale-state recovery are covered.
6. Modern forwarding and independent offline-mode preflight remain mandatory and external files remain untouched.
7. Pinned downloads remain checksum-verified, atomic, and race-safe.
8. Lobby ZIP installation retains every documented unsafe-entry rejection and existing-world immutability.
9. Managed ops retain Java-compatible offline UUID behavior; external servers are never op'd.
10. A failed verification or interrupted task cannot broaden cleanup to another owner or external process.
11. Documentation and CI describe Gradle-only usage and no longer advertise removed scripts.
