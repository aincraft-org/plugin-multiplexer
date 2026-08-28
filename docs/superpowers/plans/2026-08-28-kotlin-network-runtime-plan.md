# Kotlin Development-Network Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shell network harness with a centralized Kotlin runtime and expose its supported operations through nine Gradle tasks for consuming projects.

**Architecture:** The `network` composite build will contain a Gradle-independent `runtime` JVM subproject that produces a self-contained fat JAR. The Gradle plugin embeds that JAR, extracts it to a verified Gradle-user-home cache, and launches it with direct `ProcessBuilder`; the runtime owns all registry, configuration, process, map, download, status, and controller behavior. The repository root remains a base-only quality wrapper.

**Tech Stack:** Kotlin 2.4.0, Gradle 9.7.1, JVM bytecode 21 for plugin/runtime, Java 25 runtime requirement for Velocity/Paper, JDK `ProcessBuilder`/`ProcessHandle`/`HttpClient`/`ZipFile`/`FileChannel`/Unix-domain sockets, Gradle TestKit, JUnit Platform, temporary-directory fixture processes.

**Spec:** `docs/superpowers/specs/2026-08-28-kotlin-network-runtime-design.md`

## Global Constraints

- Gradle tasks are the only supported public entry point; remove all `bin/*.sh` commands and all shell execution.
- Expose exactly these nine task names in the `network` group: `runProxy`, `registerBackend`, `unregisterBackend`, `runBackend`, `runNetwork`, `stopNetwork`, `reloadNetwork`, `restartBackend`, and `networkStatus`.
- Runtime tasks exist only in projects applying `io.github.development-network`; the repository root remains a quality-only wrapper.
- Preserve proxy/lobby, managed-backend, and external-registration ownership boundaries; external processes and files are never started, stopped, edited, deployed to, or op'd.
- Preserve `runtime/backends.txt`, `<name>.port`, `<name>.owner`, `<name>.pid`, `<name>.ready`, `<name>.auto-dir`, `proxy.owner`, `proxy.pid`, `proxy.ready`, `proxy.lock`, `register.lock`, `velocity.toml`, `forwarding.secret`, and control state semantics.
- Persisted port wins over explicit port, explicit port wins over sorted default; managed automatic allocation skips occupied and reserved ports; names match `[A-Za-z0-9_-]+`.
- `proxy.lock` covers the infrastructure controller lifetime; `register.lock` serializes registry, ownership, port, config, and reload mutations; artifact downloads use per-destination locks and atomic same-directory replacement.
- Require independent offline-mode and modern-forwarding preflight for proxy, lobby, and every backend; Paper uses `online-mode=false` and `spigot.yml` `settings.bungeecord: false`.
- Preserve Velocity 4.1.1 build 24 SHA-256 `846411d2d0560fed0f23496ffb89681be528d2c0650ecdcf21724d2d7bd9c1ee` and Paper 26.2 build 119 SHA-256 `a8c9140c3075bd7c04973e9cdc491b21fbe6bad472b674ef932a4ae0fec19629`.
- Pinned downloads require existing-file verification, streaming SHA-256, same-directory temporary files, atomic rename, and cleanup after failure.
- Lobby ZIP validation must reject absolute paths, traversal, backslashes, duplicates, symlinks, special file types, malformed archives, and invalid world roots before extraction; existing `world/level.dat` is immutable.
- Managed ops use Java `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` at level 4; external servers never receive ops.
- Long-lived tasks block; cancellation is interrupt-aware and cleanup verifies owner plus process start identity before signaling. Never use `pkill`, process-name matching, or an unverified PID.
- Every production behavior is introduced by a test that was observed failing first; each task runs its focused tests before commit and skips project-wide validation until the final verification phase.

---

### Task 1: Runtime module and verified launcher artifact

**Files:**
- Create: `network/runtime/build.gradle.kts`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/RuntimeMain.kt`
- Create: `network/src/main/kotlin/io/github/developmentnetwork/RuntimeArtifactLauncher.kt`
- Modify: `network/settings.gradle.kts`
- Modify: `network/build.gradle.kts`
- Test: `network/src/test/kotlin/io/github/developmentnetwork/RuntimePackagingTest.kt`

**Interfaces:**
- Produces `io.github.developmentnetwork.runtime.RuntimeMainKt` with an argument parser that accepts a command token and `--key=value` settings, returning nonzero for unknown/missing commands.
- Produces `RuntimeArtifactLauncher.extract(gradleUserHome: File, classLoader: ClassLoader): File` and `RuntimeArtifactLauncher.launch(projectDir: File, gradleUserHome: File, request: List<String>): Process`.
- Embeds the runtime artifact at `META-INF/development-network/runtime.jar` in the plugin JAR.
- Consumes the existing Kotlin 2.4.0/JVM 21 toolchain and must not add Gradle API dependencies to the runtime subproject.

- [ ] **Step 1: Write failing packaging and launcher tests.**

  `RuntimePackagingTest` must create a temporary fake classloader resource and assert that extraction writes a verified JAR, reuses a matching file, replaces a checksum-mismatched file, serializes concurrent extraction, and deletes failed temporary output. Add a Gradle TestKit case that builds the `network` artifact and asserts the published plugin JAR contains the exact `META-INF/development-network/runtime.jar` entry. Add a runtime-main test for unknown command failure.

- [ ] **Step 2: Run focused tests and confirm the expected red failure.**

  Run:

  ```bash
  ./gradlew -p network test --tests io.github.developmentnetwork.RuntimePackagingTest
  ```

  Expected: compilation or assertion failures because the runtime project, resource, and launcher do not exist.

- [ ] **Step 3: Add the runtime subproject and fat-JAR packaging.**

  In `network/settings.gradle.kts`, include `:runtime` and set its project directory to `runtime`. In `network/runtime/build.gradle.kts`, apply Kotlin/JVM, use version `2.4.0`, target JVM 21, set the main class to `io.github.developmentnetwork.runtime.RuntimeMainKt`, and configure the JAR to include runtime class output plus `runtimeClasspath` dependencies with `Main-Class` set. Keep dependencies limited to Kotlin stdlib/JDK.

  In `network/build.gradle.kts`, make the plugin JAR depend on `:runtime:jar` and copy that JAR as `META-INF/development-network/runtime.jar`. Make root `check`/`assemble` reach the runtime subproject through the existing nested quality build.

- [ ] **Step 4: Implement verified extraction and direct JVM launch.**

  `RuntimeArtifactLauncher` must open the embedded resource, compute SHA-256, use a cache path below the supplied Gradle user home keyed by digest, lock extraction with `FileChannel.lock()`, verify any existing target, stream to a same-directory temporary file, verify again, and atomically move it. Launch `${System.getProperty("java.home")}/bin/java -jar <cached-runtime.jar> ...` with inherited I/O and the consuming project directory; fail with an actionable exception when the resource or Java executable is missing.

  `RuntimeMain` should only parse the command/request envelope at this task; controllers and command behavior arrive in later tasks. Keep it executable with `java -jar`.

- [ ] **Step 5: Run focused tests and commit.**

  Run the focused test command again and verify the published plugin JAR test, clean extraction, checksum mismatch, and concurrency cases pass. Commit only Task 1 files with message `Add embedded Kotlin runtime artifact`.

### Task 2: Runtime state, ownership, registry, and port rules

**Files:**
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/model/NetworkModel.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/state/RuntimeLayout.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/state/AtomicFiles.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/state/FileLocks.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/registry/RegistryStore.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/registry/PortAllocator.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RegistryRulesTest.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RuntimeStateTest.kt`

**Interfaces:**
- `data class RuntimeLayout(val base: Path)` exposes `runtimeDir`, `binariesDir`, `logsDir`, `velocityConfig`, `forwardingSecret`, `registryFile`, `proxyLock`, and per-backend state paths without stringly duplicated path math.
- `object BackendNames { fun validate(raw: String): BackendName }` validates `[A-Za-z0-9_-]+`; `data class BackendName(val value: String)` is the validated value object.
- `enum class OwnershipMode { MANAGED, EXTERNAL }`; `data class ProcessIdentity(val pid: Long, val startInstant: Instant?, val executable: Path?, val workingDirectory: Path?)`.
- `data class BackendRegistration(val name: BackendName, val port: Int, val owner: String, val mode: OwnershipMode, val process: ProcessIdentity?)`.
- `class RegistryStore(private val layout: RuntimeLayout)` reads/writes sorted unique registry names and atomically updates port/owner/mode state.
- `class PortAllocator` exposes `allocate(name, registry, persisted, explicit, occupied, reserved): Int` with persisted → explicit → sorted-index/default precedence and managed upward scanning.
- `class FileLocks` exposes `withProxyLock`, `withRegistrationLock`, and `withArtifactLock` using blocking `FileChannel` locks and `use`/`finally` release.

- [ ] **Step 1: Write failing pure-rule and state tests.**

  Cover invalid names, sorted/deduplicated registry persistence, persisted-port precedence, explicit and default allocation, occupied/reserved scanning, invalid/colliding ports, atomic state writes, and lock exclusion between two channels. Assert that external registrations have no process identity and that a second owner cannot replace a name.

- [ ] **Step 2: Run focused tests and confirm red.**

  ```bash
  ./gradlew -p network :runtime:test --tests '*RegistryRulesTest' --tests '*RuntimeStateTest'
  ```

  Expected: missing production types or failed assertions.

- [ ] **Step 3: Implement validated models, layout, atomic files, and locks.**

  Use `Path`/`Files`, UTF-8, same-directory temporary files, `ATOMIC_MOVE` with a safe non-atomic fallback only where the platform rejects the atomic option, and `FileChannel.lock()` for serialization. Never use shell commands. Persist one registry name per line and keep state files owner-aware.

- [ ] **Step 4: Implement registry transitions and port allocation.**

  Read runtime state under the registration lock, reject duplicate names/ports and owner mismatches, preserve persisted ports, scan managed automatic ports from `30067 + sortedIndex`, and reserve all current claims before selecting the next candidate. Keep proxy `25565` and lobby `30066` collision checks in the allocator API.

- [ ] **Step 5: Run focused tests and commit.**

  Verify all registry/port/lock tests pass and commit `Add runtime state registry and port rules`.

### Task 3: Configuration, preflight, downloads, maps, ops, and status primitives

**Files:**
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/config/VelocityConfigWriter.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/config/PaperConfigWriter.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/config/OpsWriter.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/config/OfflinePreflight.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/artifact/ArtifactFetcher.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/artifact/LobbyMapInstaller.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/status/MinecraftStatusProbe.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ConfigurationSafetyTest.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ArtifactSafetyTest.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/LobbyMapInstallerTest.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/MinecraftStatusProbeTest.kt`

**Interfaces:**
- `class VelocityConfigWriter { fun write(layout: RuntimeLayout, config: VelocityConfig): Path }` writes deterministic `velocity.toml` and `forwarding.secret`.
- `class PaperConfigWriter { fun writeManaged(workDir: Path, config: PaperConfig): Unit }` writes offline modern-forwarding Paper config and Bungee-off configuration; it has no external-server write method.
- `class OpsWriter { fun write(workDir: Path, users: List<String>): Path }` writes level-4 `ops.json` using Java-compatible offline UUIDs.
- `class OfflinePreflight { fun verifyProxy(...); fun verifyPaper(...): PreflightResult }` distinguishes owned files that may be regenerated from external files that must fail closed.
- `class ArtifactFetcher(private val http: HttpClient) { fun fetch(url: URI, expectedSha256: String, destination: Path): Path }` implements locked atomic fetch/reuse.
- `class LobbyMapInstaller(private val fetcher: ArtifactFetcher) { fun install(workDir: Path, options: LobbyMapOptions): MapInstallResult }` supports no-map, static pinned, and random URL modes with immutable-world behavior.
- `class MinecraftStatusProbe { fun probe(host: String, port: Int, timeout: Duration): ServerStatus }` implements the existing handshake/status packet and returns reachability/version/MOTD/player data.

- [ ] **Step 1: Write failing safety tests before implementation.**

  Tests must cover deterministic Velocity output and `try=[lobby,...]`, Paper offline/forwarding settings, preflight rejection of online/unknown external modes, exact offline UUID, existing-file checksum reuse and mismatch, failed download cleanup, all unsafe ZIP entry categories (absolute, `..`, backslash, duplicate, symlink, device/FIFO/socket/special attributes, malformed archive), root and one-top-level-folder `level.dat`, extraction escape protection, immutable existing world, static/random mode exclusivity, and status response parsing/error.

  Use generated ZIP bytes and temporary directories. For artifact tests use a local `HttpServer` fixture so no real network or pinned artifact is downloaded.

- [ ] **Step 2: Run focused tests and confirm red.**

  ```bash
  ./gradlew -p network :runtime:test --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
  ```

- [ ] **Step 3: Implement deterministic configuration and preflight.**

  Port the current Velocity fields exactly, including Velocity 4.1.1 build 24, modern forwarding, target host, online-mode choice, lobby-first failover, and sorted persisted backend ports. Generate managed Paper properties with `online-mode=false`, the shared secret, and `settings.bungeecord=false`. Check proxy, lobby, managed, and external modes independently before any start. Never edit an external path.

- [ ] **Step 4: Implement artifact fetching and ops.**

  Use `HttpClient` response streaming into a same-directory temporary file while updating SHA-256. Lock the destination, verify existing files, compare exact lowercase hex, atomically move only verified output, and delete failed temporary files. Use `MessageDigest.getInstance("MD5")` and `UUID.nameUUIDFromBytes` semantics exactly for offline ops.

- [ ] **Step 5: Implement safe lobby-map installation.**

  Inspect `ZipEntry` names and Unix/DOS external attributes before extracting. Reject every unsafe path/file type before writing any world file. Validate world-root shape and `level.dat`, extract to a sibling temporary directory, then atomically install the world directory. Remove random temporary archives after successful installation and never overwrite an existing world.

- [ ] **Step 6: Implement the status protocol and run focused tests.**

  Parse the VarInt-framed status response with bounded connect/read timeouts, report proxy status separately from backend reachability, and return a structured failure for unavailable endpoints. Run the focused command again and commit `Add runtime configuration and safety primitives`.

### Task 4: Process identity, supervision, readiness, and authenticated control

**Files:**
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/process/ProcessIdentityReader.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/process/ProcessSupervisor.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/process/ReadinessProbe.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/ControlProtocol.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/ControlServer.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/ControlClient.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ProcessSupervisorTest.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ControlChannelTest.kt`

**Interfaces:**
- `class ProcessSupervisor { fun launch(command: List<String>, cwd: Path, stdin: OutputStream? = null): OwnedProcess; fun await(process: OwnedProcess); fun terminate(process: OwnedProcess, timeout: Duration): TerminationResult }`.
- `class ProcessIdentityReader { fun capture(process: Process, cwd: Path): ProcessIdentity; fun matches(identity: ProcessIdentity): Boolean }`.
- `class ReadinessProbe { fun await(host: String, port: Int, timeout: Duration): Unit }`.
- `class ControlServer { fun serve(socket: Path, token: String, handler: (ControlCommand) -> ControlResponse): Closeable }`.
- `class ControlClient { fun request(socket: Path, token: String, command: ControlCommand, timeout: Duration): ControlResponse }`.
- `sealed interface ControlCommand { data object Reload; data object Shutdown }`.

- [ ] **Step 1: Write failing process/control tests.**

  Add a fixture JVM process that records a marker, sleeps, and handles a normal termination signal. Test captured identity, refusal to signal a changed/reused PID, graceful termination followed by force escalation, readiness timeout, socket token acceptance, wrong-token rejection, stale-socket failure, reload response, and shutdown response. Assert control/socket/token files use owner-only permissions where POSIX permissions are available.

- [ ] **Step 2: Run focused tests and confirm red.**

  ```bash
  ./gradlew -p network :runtime:test --tests '*ProcessSupervisorTest' --tests '*ControlChannelTest'
  ```

- [ ] **Step 3: Implement identity and direct process supervision.**

  Launch Java processes directly, retain stdin streams in the owning controller, capture process start identity and expected working directory, wait interruptibly, terminate descendants/owned process gracefully, wait the specified deadline, then force-kill only matching processes. Restore interrupt status after interruption. Do not use `Runtime.addShutdownHook` as the ownership mechanism.

- [ ] **Step 4: Implement Unix-domain control with secure state.**

  Bind a Unix-domain socket under runtime, generate a cryptographically random token, write token/socket state atomically with owner-only permissions, and require both the token and matching controller lease before accepting `reload` or `shutdown`. Remove stale socket files only after proving no matching controller is alive.

- [ ] **Step 5: Run focused tests and commit.**

  Verify all process/control tests pass, including external PID/file non-interference fixtures, and commit `Add runtime process supervision and control channel`.

### Task 5: Runtime controllers and command dispatcher

**Files:**
- Modify: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/RuntimeMain.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/InfrastructureController.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/ManagedBackendController.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/RegistrationService.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/UnregistrationService.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/ReloadService.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/StopService.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/RestartService.kt`
- Create: `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/StatusService.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ControllerLifecycleTest.kt`
- Test: `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RegistrationOwnershipTest.kt`

**Interfaces:**
- `sealed interface RuntimeCommand` has `ServeProxy`, `ServeFull`, `ServeBackend`, `RegisterExternal`, `UnregisterExternal`, `StopNetwork`, `ReloadNetwork`, `RestartBackend`, and `NetworkStatus` requests with explicit typed arguments.
- `class InfrastructureController { fun run(mode: InfrastructureMode, request: RuntimeRequest): Int }` owns proxy/lobby and, in full mode, one managed backend.
- `class ManagedBackendController { fun run(request: ManagedBackendRequest): Int }` owns exactly one Paper process and its registration.
- Services expose `execute(request): Int` and use Task 2–4 interfaces; they never invoke shell commands.

- [ ] **Step 1: Write failing controller integration tests.**

  Use fake Java server processes and temporary runtime bases to cover proxy-lock exclusivity, proxy-only not starting registered backends, full-mode ownership, lobby restart after unexpected exit and no restart after intentional shutdown, managed registration cleanup, external registration untouched after unregister/stop/restart, concurrent registration serialization, reload socket delivery, stop fallback identity checks, and persisted-port preservation.

- [ ] **Step 2: Run focused tests and confirm red.**

  ```bash
  ./gradlew -p network :runtime:test --tests '*ControllerLifecycleTest' --tests '*RegistrationOwnershipTest'
  ```

- [ ] **Step 3: Implement registration/unregistration/reload services.**

  Under `register.lock`, validate owners and names, resolve claims, persist state, and roll back only this run's state on failure. External registration verifies reachability and every preflight condition without writing external files, then regenerates config and sends authenticated reload. Unregistration removes only a matching owner and leaves external Paper alive. Reload is deterministic and idempotent.

- [ ] **Step 4: Implement infrastructure and managed-backend controllers.**

  Proxy/full controller acquires `proxy.lock`, resolves registry/ports, runs preflight, fetches pinned artifacts, writes configs, creates control state, starts proxy and supervised lobby, optionally starts only its controller-owned managed backend, waits for readiness, and serves control commands. Managed controller deploys one JAR/config, registers one managed owner, starts Paper, waits, and cleans up only its owner on exit. Keep the documented two-second lobby restart and graceful termination deadlines.

- [ ] **Step 5: Implement stop/restart/status commands and dispatcher.**

  `stopNetwork` asks the authenticated controller to shut down, waits for lease disappearance, and falls back only to owner/start-identity-verified controller-owned managed processes. `restartBackend` retains the persisted port and affects one managed backend. `networkStatus` probes all registered endpoints and returns nonzero for required failures. Add exit-code and diagnostic mapping in `RuntimeMain`.

- [ ] **Step 6: Run focused tests and commit.**

  Run both focused test classes and the complete runtime test set once the task's tests are green. Commit `Implement Kotlin network controllers`.

### Task 6: Gradle plugin task adapters and consuming-project contract

**Files:**
- Create: `network/src/main/kotlin/io/github/developmentnetwork/DevelopmentNetworkExtension.kt`
- Modify: `network/src/main/kotlin/io/github/developmentnetwork/DevNetworkPlugin.kt`
- Create: `network/src/main/kotlin/io/github/developmentnetwork/NetworkTaskSupport.kt`
- Modify: `network/src/test/kotlin/io/github/developmentnetwork/DevNetworkPluginSurfaceTest.kt`
- Create: `network/src/test/kotlin/io/github/developmentnetwork/ConsumingProjectIntegrationTest.kt`

**Interfaces:**
- `abstract class DevelopmentNetworkExtension` exposes provider-backed settings for existing `network*` properties, target host, users, online mode, and map options.
- Nine abstract task classes remain `DefaultTask` types in group `network`, with exact existing descriptions for the five retained tasks and explicit descriptions for the four additions.
- `NetworkTaskSupport.run(project: Project, command: RuntimeCommand, longLived: Boolean): Int` resolves/extracts/launches the embedded runtime and handles interruption.
- `runBackend`, `runNetwork`, and `restartBackend` depend on the configured `Jar` task and pass its actual `archiveFile`; `registerBackend` validates required port before launch.

- [ ] **Step 1: Write failing TestKit tests for the new task surface.**

  Replace source-text assertions with behavior tests that apply the plugin in a temporary consumer, inspect all nine tasks/descriptions, verify provider/property defaults and invalid values, verify missing external port fails before runtime launch, and verify managed tasks use the archive file. Add a composite consumer whose `settings.gradle.kts` uses `includeBuild(<worktree>/network)` and whose `build.gradle.kts` applies the plugin. Add a published-artifact-style test that inspects a clean Gradle user home and verifies runtime extraction from the plugin JAR.

- [ ] **Step 2: Run focused TestKit tests and confirm red.**

  ```bash
  ./gradlew -p network test --tests io.github.developmentnetwork.DevNetworkPluginSurfaceTest --tests io.github.developmentnetwork.ConsumingProjectIntegrationTest
  ```

  Expected: missing task names, old shell-path assumptions, and missing runtime extraction failures.

- [ ] **Step 3: Implement the extension and task registrations.**

  Preserve `networkBase`, `networkBackend`, `networkBackendPort`, `networkProxyPort`, `networkJarTask`, `networkDevUsers`, `networkOnlineMode`, and `networkRegistrationOwner` behavior. Remove `harnessBin`, shell path discovery, script command construction, inherited shell environment handling, and old process helpers. Register exactly nine abstract task classes and mark side-effecting tasks non-cacheable.

- [ ] **Step 4: Implement typed task adapters.**

  Each task builds an explicit runtime request. Long-lived commands wait on the runtime child and perform bounded interruption cleanup; short commands return after the runtime response. `runProxy` owns proxy mode, `runNetwork` full mode, `runBackend` backend mode, and register/unregister never wait on a Paper PID. Preserve useful inherited console output and map nonzero runtime exits to `GradleException`.

- [ ] **Step 5: Run focused TestKit tests and commit.**

  Verify the actual include-build consumer, clean-user-home extraction, task surface, descriptions, property validation, jar wiring, and long-lived/short-lived semantics. Commit `Expose Kotlin network runtime through Gradle tasks`.

### Task 7: Documentation, CI, and shell CLI removal

**Files:**
- Delete: `bin/boot-backend.sh`
- Delete: `bin/boot-external.sh`
- Delete: `bin/boot-lobby.sh`
- Delete: `bin/boot-proxy.sh`
- Delete: `bin/dev-network-status.sh`
- Delete: `bin/dev-network.sh`
- Delete: `bin/fetch-jar.sh`
- Delete: `bin/install-lobby-map.sh`
- Delete: `bin/reload-network.sh`
- Delete: `bin/register-backend.sh`
- Delete: `bin/restart-backend.sh`
- Delete: `bin/stop-dev-network.sh`
- Delete: `bin/test-lobby-map.sh`
- Delete: `bin/test-network.sh`
- Delete: `bin/unregister-backend.sh`
- Delete: `bin/velocity-toml.sh`
- Delete: `bin/write-ops.sh`
- Modify: `README.md`
- Modify: `SKILL.md`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/nightly.yml`
- Modify: `.github/workflows/release.yml` only where shell quality commands or script packaging are referenced
- Modify: `AGENTS.md` if it is tracked in the implementation branch
- Test: documentation/reference search and CI YAML parse checks

**Interfaces:**
- Documentation examples use `./gradlew` tasks from an applying consumer project and no longer advertise `bin/`, `DEV_NETWORK_BIN`, or `DEV_NETWORK_DIR`.
- The ownership matrix, agent split, port/preflight/forwarding/map/status caveats, artifact pins, and include-build instructions remain present with Gradle task equivalents.

- [ ] **Step 1: Write failing reference checks.**

  Add a deterministic test or verification script under the existing Kotlin test infrastructure that scans README/SKILL/AGENTS and workflow files for removed shell commands, required nine task names, include-build instructions, and retained safety/ownership phrases. Make it fail against the current documentation before editing.

- [ ] **Step 2: Run the reference checks and confirm red.**

  Run the focused documentation check and record failures for shell examples/tree listings and missing new task references.

- [ ] **Step 3: Update documentation and CI.**

  Rewrite command examples and repository trees to Gradle-only usage, document the embedded runtime and clean consumer setup, replace shell quality commands with Gradle/runtime tests, and retain the full map/archive safety contract. Remove shell test/lint workflow steps without weakening the Java/Kotlin quality gate.

- [ ] **Step 4: Delete the obsolete shell implementation.**

  Delete only the listed scripts whose behavior is now owned by runtime Kotlin services. Confirm no Kotlin code, docs, workflow, or test references a removed path. Do not delete runtime-generated directories or unrelated user files.

- [ ] **Step 5: Run focused reference checks and commit.**

  Verify documentation and workflow checks pass and commit `Remove shell harness and document Gradle runtime tasks`.

### Task 8: Integrated verification and final cleanup

**Files:**
- Modify only files required by failures found in Tasks 1–7 after review.
- Test: all `network/runtime/src/test/kotlin/**`
- Test: all `network/src/test/kotlin/**`
- Test: actual temporary consuming project through `includeBuild`

**Interfaces:**
- Uses the complete nine-task plugin and embedded runtime from prior tasks.
- Does not add new behavior or broaden scope; fixes only verified integration gaps.

- [ ] **Step 1: Run the complete repository quality gate.**

  From the isolated worktree:

  ```bash
  ./gradlew clean check
  ./gradlew assemble
  ```

  Confirm runtime tests, plugin tests, embedded-resource packaging, metadata verification, and both module artifacts complete with exit code 0.

- [ ] **Step 2: Run an actual consumer smoke scenario.**

  Create a temporary applying project with `settings.gradle.kts` containing `includeBuild(<worktree>/network)` and apply `io.github.development-network`. Exercise `tasks --group=network`, property validation, runtime extraction into a clean Gradle user home, a fixture-backed `networkStatus`, and registration/unregistration against fixture endpoints. Do not download or launch real Velocity/Paper in unit verification.

- [ ] **Step 3: Exercise lifecycle safety fixtures.**

  Run the controller integration fixtures for proxy lock exclusivity, managed cleanup, external survival, reload authentication, stale lease recovery, port persistence, and unsafe map archives. Capture command output and exit codes as evidence.

- [ ] **Step 4: Review the complete diff and implementation contract.**

  Check every former script in the migration table has one Kotlin owner, every nine task has a public adapter, every acceptance criterion has direct test or smoke evidence, docs match the task API, and no unrelated user changes were overwritten. Resolve any Critical/Important review findings before claiming completion.

- [ ] **Step 5: Commit only verified fixes.**

  Commit integration fixes separately with a behavior-specific message, then record final test commands and outputs in the SDD ledger.
