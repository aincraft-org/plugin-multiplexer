# Task 3 Implementation Report

## Scope

Implemented Task 3 only: deterministic Velocity and managed Paper configuration, offline-forwarding preflight, Java-compatible offline ops generation, pinned atomic artifact fetching, immutable safe lobby-map installation, and Minecraft status protocol probing. No process supervision, control sockets, controllers, command dispatch, Gradle task adapters, documentation, or shell deletion was added.

## Changes

- Added `VelocityConfigWriter` and `VelocityConfig`:
  - writes the canonical Velocity 4.1.1-compatible `config-version = "2.8"` configuration;
  - preserves the development MOTD, modern forwarding, fixed forwarding-secret path, configured proxy bind port, target host, online-mode choice, advanced/query settings, and sorted backend entries;
  - emits `try = ["lobby", ...]` with lobby first;
  - reads and honors sorted persisted registry names and persisted backend ports;
  - atomically writes `velocity.toml` and `forwarding.secret`.
- Added `PaperConfigWriter` and `PaperConfig`:
  - writes only managed work-directory files;
  - emits `server.properties` with selected port and `online-mode=false`;
  - enables Paper Velocity modern forwarding with the shared secret;
  - writes accepted EULA and `settings.bungeecord: false`.
- Added `OfflinePreflight` and `PreflightResult`:
  - verifies proxy offline mode independently;
  - verifies Paper authentication mode, modern forwarding, shared secret, and Bungee forwarding state;
  - distinguishes managed/owned and external/unknown diagnostics and has no external-server write path;
  - supports an explicit owned-proxy regeneration callback followed by re-checking.
- Added `OpsWriter`:
  - validates development usernames;
  - computes `OfflinePlayer:<name>` UUIDs with MD5 and Java UUID version/variant semantics;
  - emits deterministic level-4 `ops.json`.
- Added `ArtifactFetcher`:
  - uses per-destination blocking sibling locks;
  - verifies an existing destination before reuse;
  - removes stale mismatches;
  - streams HTTP responses into same-directory temporary files while hashing SHA-256;
  - compares exact lowercase expected checksums;
  - atomically moves only verified output and cleans temporary files on all failures;
  - exposes an unpinned streaming download operation for random map selections, returning the computed checksum.
- Added `LobbyMapInstaller` and map result/options types:
  - validates mutually exclusive no-map, static pinned, and random URL modes;
  - keeps an existing complete world immutable and rejects incomplete existing roots;
  - reads ZIP central-directory metadata before opening entry streams;
  - rejects absolute, traversal, backslash/drive, duplicate, symlink, device/FIFO/socket/special, DOS volume-label, malformed, and inconsistent directory entries;
  - protects extraction targets with normalized-root checks;
  - validates root `level.dat` or exactly one top-level world directory containing `level.dat`;
  - extracts to a sibling temporary directory and atomically installs the world without replacement;
  - removes downloaded map archives after installation or failure.
- Added `MinecraftStatusProbe` and `ServerStatus`:
  - uses bounded socket connect/read timeouts;
  - sends the status handshake and request with VarInt framing;
  - bounds packet and JSON sizes;
  - parses version, string/component/list MOTDs, and player counts with a small dependency-free JSON parser;
  - returns structured unavailable/malformed failures without conflating endpoint reachability with proxy routing.
- Added focused tests covering deterministic configuration, Paper forwarding/offline settings, unknown/online preflight, exact offline UUID, existing artifact reuse/replacement, failed-download cleanup, ZIP world shapes, every requested unsafe ZIP category, malformed roots, immutable worlds, mode exclusivity, random archive cleanup, and status parsing/failure.

## TDD Evidence

Required red run after writing the four test classes and before production implementation:

```text
./gradlew -p network :runtime:test --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
```

It failed during test compilation with unresolved Task 3 production packages/types, as expected for newly written tests.

Focused verification after implementation (rerun without relying on prior task outputs):

```text
./gradlew -p network :runtime:test --rerun-tasks --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
```

Result: `BUILD SUCCESSFUL`; 14 focused tests completed with zero failures or errors.

No formatter, linter, project-wide build, or project-wide test command was run.

## Commit

`e96535a Add runtime configuration and safety primitives`

## Preserved Changes

Pre-existing uncommitted changes in `README.md`, `SKILL.md`, `bin/`, and `docs/` remain unmodified and unstaged.

## Concerns

- Runtime source paths match the repository's existing ignored `network/runtime/` pattern, so the Task 3 files must be force-added explicitly when committing; unrelated user files remain unstaged.
- ZIP metadata attributes are read directly from the central directory because the target JDK does not expose `ZipEntry` external attributes as a public API; archives relying on unsupported ZIP64 central-directory records are rejected as malformed rather than extracted.
- The status probe reports endpoint reachability and server status only; real proxy routing still requires an actual client login and `/server <name>` operation as specified.

## Fix Round 1

### Files changed

- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/artifact/ArtifactFetcher.kt`
  - preserves an existing destination through download, checksum verification, and HTTP failure;
  - rejects symbolic-link destinations without hashing or replacing the link;
  - uses create-new temporary output before verified atomic replacement.
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/artifact/LobbyMapInstaller.kt`
  - serializes installation per work directory;
  - re-checks the world immediately before a no-replace move and preserves a concurrently created world.
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/config/OfflinePreflight.kt`
  - parses scoped `proxies.velocity` and `settings` properties;
  - rejects duplicate effective properties;
  - invokes owned-proxy regeneration for missing configuration;
  - verifies proxy offline mode, modern forwarding, forwarding-secret-file, and expected secret.
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/config/PaperConfigWriter.kt`
  - shares the immutable development forwarding secret and rejects unsynchronized custom values.
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/config/VelocityConfigWriter.kt`
  - shares the immutable development forwarding secret and rejects unsynchronized custom values.
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/status/MinecraftStatusProbe.kt`
  - bounds recursive JSON depth and concatenates structured MOTD `extra` text.
- Focused tests:
  `ConfigurationSafetyTest.kt`, `ArtifactSafetyTest.kt`, `LobbyMapInstallerTest.kt`, and `MinecraftStatusProbeTest.kt`.

### TDD evidence

After writing the fix-round tests and before implementing the production changes, the required command was run:

```text
./gradlew -p network :runtime:test --rerun-tasks --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
```

It failed during `:runtime:compileTestKotlin` with the expected missing production API behavior (`verifyProxy(... forwardingSecret ...)`, `VelocityConfig.forwardingSecret`, and the no-argument `ArtifactFetcher` constructor), before any focused tests could execute.

### Final focused verification

```text
./gradlew -p network :runtime:test --rerun-tasks --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
```

Result:

```text
BUILD SUCCESSFUL in 3s
4 actionable tasks: 4 executed
Consider enabling configuration cache to speed up subsequent builds: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html


Wall time: 3.26 seconds
```

The focused XML reports recorded 24 tests total: ConfigurationSafetyTest 8, ArtifactSafetyTest 5, LobbyMapInstallerTest 8, and MinecraftStatusProbeTest 3; all had `failures="0"`, `errors="0"`, and `skipped="0"`.

No formatter, linter, project-wide build, project-wide test, later controller, or Gradle task work was run.

### Concerns

- Lobby installation uses the JDK provider's same-directory no-replace move after a final destination re-check; it intentionally does not pass `REPLACE_EXISTING`.
- Preflight intentionally fails closed for malformed/unsupported properties and rejects duplicate keys rather than relying on parser last-value behavior.
- Existing unrelated changes in `README.md`, `SKILL.md`, `bin/`, `docs/`, and `AGENTS.md` were not staged.

## Fix Round 2

### Files changed

- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/artifact/LobbyMapInstaller.kt`
  - claims the absent `world` path with an atomically created empty directory reservation before the final publication check;
  - records the reservation directory identity and refuses publication if the claim is lost or populated;
  - publishes the fully validated extraction only with `ATOMIC_MOVE` and `REPLACE_EXISTING`, replacing only the installer's empty reservation;
  - fails closed when the provider cannot perform the atomic publication or refuses reservation replacement;
  - preserves a creator that populates the reservation after the final check, including the default Unix provider's generic `FileSystemException` response.
- `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/LobbyMapInstallerTest.kt`
  - adds a deterministic final-window creator test that observes the empty reservation, attempts an empty/incomplete world creation after the final reservation check, and verifies no map files are published over it.

### TDD evidence

After adding the final-window race test and before implementing the reservation protocol, the required command was run:

```text
./gradlew -p network :runtime:test --rerun-tasks --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
```

It failed during `:runtime:compileTestKotlin` because `LobbyMapInstaller` did not yet expose the test hook used to place the creator after the final check (`Too many arguments for constructor(fetcher: ArtifactFetcher)`).

After implementing the reservation protocol, the same focused command was rerun:

```text
./gradlew -p network :runtime:test --rerun-tasks --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
```

Result: `BUILD SUCCESSFUL`; 25 focused tests completed with zero failures or errors, including 9 `LobbyMapInstallerTest` cases.

No formatter, linter, project-wide build, project-wide test, later controller, or Gradle task work was run.

### Concerns

- The reservation is an empty directory, so readers can observe an empty `world` path during installation but can never observe partially extracted map content at that path.
- Publication requires a filesystem provider that supports atomic directory replacement; unsupported providers fail closed without a non-atomic fallback. Directory identity is also required to verify ownership of the reservation.
- The focused race test uses an internal test-only callback to deterministically run the creator after reservation verification and before atomic publication.

## Fix Round 3

### Files changed

- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/artifact/LobbyMapInstaller.kt`
  - replaces the visible empty `world` reservation with a POSIX mode-000 reservation created with a `FileAttribute`; readers and ordinary creators cannot open or populate the destination while extraction is in progress;
  - verifies the reservation's POSIX mode and filesystem identity before publication and again after the deterministic race seam, preserving any delete/recreate winner without deleting it;
  - removes `REPLACE_EXISTING`; publication uses only `ATOMIC_MOVE` after the map has been fully validated and extracted;
  - probes the destination provider before downloading for a POSIX permission view, creation of an inaccessible directory, and atomic directory publication; missing/unsupported capabilities fail closed with `IOException` and no non-atomic fallback;
  - keeps installer locking, archive cleanup, ZIP metadata/path validation, and immutable existing-world behavior unchanged.
- `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/LobbyMapInstallerTest.kt`
  - adds reader/creator visibility assertions for mode-000 reservations;
  - adds deterministic populated and empty delete/recreate final-window tests and verifies the creator's world is never replaced;
  - adds unsupported ZIP filesystem provider coverage and checks extracted/archive temporary cleanup for successful and creator-race paths.

### TDD evidence

After writing the fix-round-3 tests and before the production protocol change, the required focused command was run:

```text
./gradlew -p network :runtime:test --rerun-tasks --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
```

It failed with the expected new behavior assertions: the old visible reservation had non-empty permissions, and an empty delete/recreate target was replaced by the old `ATOMIC_MOVE, REPLACE_EXISTING` publication. The first attempt also exposed a test-fixture error for an empty ZIP filesystem, which was corrected before the implementation run.

After implementing the inaccessible-reservation protocol, the same focused command was rerun:

```text
./gradlew -p network :runtime:test --rerun-tasks --tests '*ConfigurationSafetyTest' --tests '*ArtifactSafetyTest' --tests '*LobbyMapInstallerTest' --tests '*MinecraftStatusProbeTest'
```

Result: `BUILD SUCCESSFUL`; 28 focused tests completed with zero failures or errors, including 12 `LobbyMapInstallerTest` cases.

No formatter, linter, project-wide build, project-wide test, later controller, or Gradle task work was run.

### Guarantee and unsupported behavior

For map modes, the destination protocol is: create `world` atomically with POSIX mode `000`, fully validate/extract beside it, verify the reservation identity and mode, then publish only with an atomic directory move. Consequently, no reader can observe a partially extracted map at the public `world` path, and a creator that deletes/recreates or populates the reservation before the final verification is preserved rather than overwritten. Providers without POSIX permissions, inaccessible-attribute enforcement, or atomic directory moves fail closed before download/publication; there is no replacement or copy fallback.

### Concerns

- The safe publication protocol intentionally requires a provider exposing POSIX permissions and atomic directory moves; providers such as ZIP filesystems are rejected rather than risking visible incomplete content.
- A caller with authority to bypass the provider's POSIX access controls is outside the ordinary reader/creator contract; identity and mode checks still reject path replacement observed before publication.
