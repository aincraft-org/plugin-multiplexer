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
