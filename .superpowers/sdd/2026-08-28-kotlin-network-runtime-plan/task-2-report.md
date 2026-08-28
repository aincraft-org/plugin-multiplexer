# Task 2 Implementation Report

## Scope

Implemented Task 2 only: runtime models, canonical runtime state layout, atomic state-file writes, blocking file locks, registry persistence and ownership transitions, ownership modes, process identity persistence, and deterministic backend port allocation.

Configuration, downloads, map installation, status probing, process control, controllers, and additional Gradle tasks were not implemented.

## Changes

- Added `NetworkModel.kt` with:
  - validated `BackendName` and `BackendNames.validate`, accepting only `[A-Za-z0-9_-]+`;
  - `OwnershipMode.MANAGED` and `OwnershipMode.EXTERNAL`;
  - validated `ProcessIdentity` (`pid`, optional start instant, executable, and working directory);
  - `BackendRegistration` with validated backend ports and owners;
  - an invariant that external registrations cannot carry process identity.
- Added `RuntimeLayout.kt` with canonical `Path` derivation for runtime, binaries, logs, proxy state, registry/registration lock state, control state, and all per-backend state paths. Backend-derived paths are constructed only from validated `BackendName` values.
- Added `AtomicFiles.kt` with UTF-8 text/line reads and same-directory temporary writes. Writes force the temporary file, use `ATOMIC_MOVE` with `REPLACE_EXISTING`, and use the documented same-directory non-atomic fallback only when the platform rejects atomic moves. Temporary output is removed in `finally`.
- Added `FileLocks.kt` with `withProxyLock`, `withRegistrationLock`, and per-artifact `withArtifactLock`. Locks use blocking `FileChannel.lock()`, retry same-JVM overlapping-lock attempts, and release channels and locks through `use` even when the protected action fails.
- Added `RegistryStore.kt` with:
  - sorted/deduplicated one-name-per-line registry persistence;
  - serialized reads and writes under `register.lock`;
  - atomic port, owner, and explicit mode state writes;
  - managed process identity persistence across pid/start/executable/working-directory state files;
  - owner-verified registration updates and unregistration;
  - duplicate-name, incomplete-name-state, duplicate-port, and owner-mismatch rejection;
  - same-owner managed/external transitions;
  - compatibility parsing for the prior key/value owner-file format;
  - removal of process identity and generated readiness/auto-directory markers when state is removed or becomes external.
- Added `PortAllocator.kt` with persisted → explicit → sorted-index/default precedence, default `30067 + sortedIndex`, occupied/reserved skipping for automatic managed allocation, range checks (`1024..65535`), and proxy `25565`/lobby `30066` collision checks.
- Added the two required focused test classes covering invalid names, canonical registry persistence, allocation precedence/scanning/collisions, ownership transitions, external process-identity exclusion, layout paths, atomic replacement, temporary cleanup, and lock exclusion.
- Added runtime test dependencies/JUnit platform configuration required to execute the new runtime tests.

## TDD Evidence

Required red run before production implementation:

```text
./gradlew -p network :runtime:test --tests '*RegistryRulesTest' --tests '*RuntimeStateTest'
```

It failed during test compilation with unresolved Task 2 model/state/registry types (and the runtime test dependencies were not yet configured), as expected for the newly written tests.

Focused verification after implementation:

```text
./gradlew -p network :runtime:test --tests '*RegistryRulesTest' --tests '*RuntimeStateTest'
```

Result:

```text
BUILD SUCCESSFUL
4 actionable tasks: 3 executed, 1 up-to-date
```

The focused suite executed 12 tests successfully.

No formatter, linter, project-wide build, or project-wide test command was run.

## Commit

`b79c386 Add runtime state registry and port rules`

## Preserved Changes

The pre-existing uncommitted changes in `README.md`, `SKILL.md`, `bin/`, and `docs/` remain unmodified and uncommitted. The Task 2 commit contains only the runtime build/test wiring and Task 2 implementation/test files.

## Concerns

- The runtime state model adds explicit `<name>.mode`, `<name>.start`, `<name>.executable`, and `<name>.working-directory` files alongside the compatibility state names. This is needed to preserve ownership mode and process identity without putting process identity on external registrations.
- Registry reads now take `register.lock`; callers must not invoke a public `RegistryStore` read method while independently holding the same lock in the same JVM. Internal transitions use unlocked helpers while holding the lock to avoid nested acquisition.
- The runtime entry point remains the Task 1 request-envelope implementation until the later controller/dispatcher task consumes these APIs.
## Review Fix Report

### Files

- Updated `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/state/AtomicFiles.kt` to fail on unsupported atomic moves without a non-atomic replacement fallback, while deleting the temporary file on every failure.
- Updated `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/registry/RegistryStore.kt` to discover persisted registration claims from state files even when omitted from `backends.txt`, and to make unregister cleanup ownership-mode aware.
- Updated `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RuntimeStateTest.kt` with focused atomic-move rejection and lock-release-on-throw tests.
- Updated `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RegistryRulesTest.kt` with hidden persisted-port and external cleanup-boundary tests.

### Focused Test Evidence

The required red run after adding the tests failed during test compilation, as expected: the atomic-move test seam was not yet implemented and the test import was incomplete.

After the fixes:

```text
./gradlew -p network :runtime:test --tests '*RegistryRulesTest' --tests '*RuntimeStateTest'
BUILD SUCCESSFUL
4 actionable tasks: 3 executed, 1 up-to-date
```

The focused suite executed 16 tests successfully: 11 `RegistryRulesTest` tests and 5 `RuntimeStateTest` tests, with zero failures or errors.

### Concerns

- External unregister removes harness registration and readiness metadata but intentionally preserves the external-mode auto-directory marker; process identity and auto-directory cleanup are managed-only.
- Persisted state discovery treats registration-bearing `.port`, `.owner`, `.mode`, and process identity files as claims; readiness and auto-directory markers alone are ancillary metadata and do not create registrations.


## Fix-Round-2 Report

### Files

- Updated `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RegistryRulesTest.kt` with `liveProxyStateDoesNotBecomeBackendWhileHiddenClaimRemainsDiscoverable`, covering live `proxy.owner`/`proxy.pid` state, hidden backend claim discovery, normal registration, and subsequent reads.
- Updated `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/registry/RegistryStore.kt` to explicitly exclude the infrastructure proxy owner and PID files from backend claim discovery while retaining suffix-based discovery for hidden backend state.

### Focused Test Evidence

The required red run after adding the regression test failed as intended:

```text
./gradlew -p network :runtime:test --tests '*RegistryRulesTest' --tests '*RuntimeStateTest'
RegistryRulesTest > liveProxyStateDoesNotBecomeBackendWhileHiddenClaimRemainsDiscoverable() FAILED
17 tests completed, 1 failed
BUILD FAILED
```

After the minimal fix:

```text
./gradlew -p network :runtime:test --tests '*RegistryRulesTest' --tests '*RuntimeStateTest'
BUILD SUCCESSFUL in 1s
4 actionable tasks: 3 executed, 1 up-to-date
```

The focused suite passes, including the new proxy-state/hidden-claim regression.

### Concerns

- No additional concerns. Proxy infrastructure exclusion is limited to the canonical `proxy.owner` and `proxy.pid` paths; hidden backend registration claims continue to be discovered from the persisted state suffixes.
