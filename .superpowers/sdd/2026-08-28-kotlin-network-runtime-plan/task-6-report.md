# Task 6 Report: Gradle plugin task adapters and consuming-project contract

## Status

Implemented in the commit titled `Expose Kotlin network runtime through Gradle tasks`.

## TDD evidence

### RED

Added the behavior-first TestKit surface and consuming-project tests before replacing the shell adapter, then ran:

```text
./gradlew -p network test --tests io.github.developmentnetwork.DevNetworkPluginSurfaceTest --tests io.github.developmentnetwork.ConsumingProjectIntegrationTest
```

The focused run failed with the expected missing-surface assertions: the consumer did not expose the four new tasks and provider/consumer contract coverage was not yet implemented. The initial test run reported two failures and two passing tests.

### GREEN

After implementation, the focused plugin tests passed with:

```text
./gradlew -p network test --tests io.github.developmentnetwork.DevNetworkPluginSurfaceTest --tests io.github.developmentnetwork.ConsumingProjectIntegrationTest -x :runtime:test
```

Observed result: `BUILD SUCCESSFUL`, six tests completed, no failures. `-x :runtime:test` is required because the network build wires runtime `check` into the plugin project and Gradle otherwise forwards the plugin `--tests` filters to the runtime project, where those class names do not exist.

Additional focused runtime evidence:

```text
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.RuntimeMainTask6Test
```

Observed result: `BUILD SUCCESSFUL`.

Plugin packaging was also verified with:

```text
./gradlew -p network test --tests io.github.developmentnetwork.RuntimePackagingTest -x :runtime:test
```

Observed result: `BUILD SUCCESSFUL`, including real plugin-JAR embedding and extraction coverage.

The managed controller lifecycle test was also run in isolation after extending runtime allocation for omitted managed ports:

```text
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.ControllerLifecycleTest.managedBackendOwnsOnePaperProcessAndCleansOnlyItsRegistration
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.ControllerLifecycleTest.fullModeStartsOnlyItsManagedBackendAndUnexpectedLobbyRestartIsDelayed
```

Both observed results were `BUILD SUCCESSFUL`. A combined invocation intermittently left controller fixture processes from an early assertion and was not used as the final evidence.

## Files changed

- Added `network/src/main/kotlin/io/github/developmentnetwork/DevelopmentNetworkExtension.kt` with provider-backed base, backend, ports, Jar task, users, online mode, owner, target host, timeouts, lobby, and map settings.
- Replaced `network/src/main/kotlin/io/github/developmentnetwork/DevNetworkPlugin.kt` shell/process helpers with exactly nine non-cacheable typed `DefaultTask` adapters and preserved the five existing descriptions.
- Added `network/src/main/kotlin/io/github/developmentnetwork/NetworkTaskSupport.kt` for deterministic runtime argv serialization, embedded launcher use, inherited I/O, bounded interruption cleanup, and nonzero-exit Gradle failures.
- Updated the root `.gitignore` runtime artifact rule to `/runtime/` so the tracked `network/runtime` source tree and its focused test remain visible.
- Updated `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/RuntimeMain.kt` so managed backend port is optional and can be allocated by the runtime.
- Updated `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/InfrastructureController.kt` to allocate omitted managed ports through `PortAllocator`, preserving persisted ports and avoiding a plugin-side fixed-port fallback.
- Added `network/src/test/kotlin/io/github/developmentnetwork/DevNetworkPluginSurfaceTest.kt` covering all nine task names/descriptions and clean Gradle-user-home extraction from the real plugin JAR.
- Added `network/src/test/kotlin/io/github/developmentnetwork/ConsumingProjectIntegrationTest.kt` covering composite consumers, provider defaults/overrides, invalid/missing external ports before launch, and configured Jar task dependency wiring.
- Added `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RuntimeMainTask6Test.kt` covering omitted managed-port parsing.

## Self-review

- No shell scripts, shell path discovery, inherited environment filtering, or old process helpers remain in the plugin adapter.
- External registration and unregistration use short runtime operations and do not manage Paper PIDs.
- Managed tasks pass the selected `Jar.archiveFile` as an explicit runtime setting; runtime-side deployment validates ownership first, requires directory/JAR operations to succeed, and removes stale JARs safely.
- Managed ownership is deterministic from the configured owner or project path/backend name, so repeated run/restart operations address the same registration.
- `registerBackend` validates missing and out-of-range ports before constructing or launching a runtime process.
- Runtime arguments use only parser-approved `--key=value` settings and explicitly carry target host, users, online mode, map options, ownership, ports, directories, and timeouts.
- The embedded runtime extraction test uses a clean temporary Gradle user home and a real plugin JAR resource rather than source-tree or shell fallbacks.

## Concerns

- Runtime auto-allocation required the minimal nullable managed-port/request change in the runtime module because the pre-existing parser required a port even though the public Gradle contract requires it only for external registration.
- The network build's runtime dependency causes focused network test filters to be forwarded to `:runtime:test`; the documented focused command excludes that unrelated filtered runtime invocation with `-x :runtime:test`.

## Review-fix report

The review follow-up moved managed plugin-JAR deployment into the runtime after ownership/work-directory validation, added strict deletion/copy checks, and added `plugin-jar` support to backend/full runtime requests. Managed backend allocation now runs under the registration transition and includes persisted claims plus proxy/lobby reservations. A same-owner live managed process is rejected rather than overwritten. Managed, full, and proxy controllers install shutdown hooks that request stop and run normal bounded cleanup; the plugin also requests owner-scoped controller shutdown before killing an interrupted long-lived runtime child.

The plugin packages the nested runtime artifact in both `processResources` and the plugin JAR, and includes runtime model classes in the top-level plugin artifact so composite and direct consumers can execute task adapters. Adapter-side managed port values are range-validated before launch.

Review-fix verification:

```text
./gradlew -p network test --tests io.github.developmentnetwork.DevNetworkPluginSurfaceTest --tests io.github.developmentnetwork.ConsumingProjectIntegrationTest -x :runtime:test
```

Observed result: `BUILD SUCCESSFUL`; six focused plugin/consumer tests passed, including a composite consumer executing `networkStatus` through the embedded runtime.

```text
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.RuntimeMainTask6Test
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.ControllerLifecycleTest.fullModeStartsOnlyItsManagedBackendAndUnexpectedLobbyRestartIsDelayed
```

Observed result: both focused runtime commands passed in isolation.

The follow-up review also restored `networkDevUsers` serialization for `runBackend`; `RuntimeMainTask6Test` now asserts comma-separated users survive parsing. The final command

```text
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.RuntimeMainTask6Test && ./gradlew -p network test --tests io.github.developmentnetwork.ConsumingProjectIntegrationTest --tests io.github.developmentnetwork.DevNetworkPluginSurfaceTest -x :runtime:test
```

completed with `BUILD SUCCESSFUL` for both focused invocations.

Latest review correction restored `--dev-users` serialization for `runBackend`; the parser-focused test now verifies `alice,bob` round-trips as two users. Atomic managed startup now creates a registration-lock-protected `.starting` marker and placeholder registration before preparation, checks occupied/reserved ports, rejects a same-owner live process or concurrent start, and removes the marker during cleanup. Verification passed:

```text
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.RuntimeMainTask6Test --tests io.github.developmentnetwork.runtime.ControllerLifecycleTest.managedBackendOwnsOnePaperProcessAndCleansOnlyItsRegistration
./gradlew -p network test --tests io.github.developmentnetwork.ConsumingProjectIntegrationTest --tests io.github.developmentnetwork.DevNetworkPluginSurfaceTest -x :runtime:test
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.ControllerLifecycleTest.fullModeStartsOnlyItsManagedBackendAndUnexpectedLobbyRestartIsDelayed
```

All three invocations completed with `BUILD SUCCESSFUL`.

The final re-review correction rejects any same-owner managed registration with a null process identity as a durable reserved/starting claim. This prevents a SIGKILL between placeholder registration and child identity publication from being overwritten by a later run; cleanup remains owner-and-port guarded. Parser, managed lifecycle, full lifecycle, and composite focused tests passed after this correction.

Final re-review fixes:

- Full-mode backend allocation now runs under `register.lock`, writes an owner-bearing `.full-starting` marker, and reserves a managed placeholder before artifact/config preparation. Concurrent managed/full starts therefore reject the null-process reservation rather than racing ownership.
- Dead markers reclaim only same-owner, managed, null-process placeholders. Reclamation preserves the persisted port, while different-owner, external, and live-process claims remain protected.
- Full cleanup now removes an unstarted placeholder only when its managed owner and port still match; it does not remove unproven live or changed claims.
- Added `managedBackendReclaimsItsDeadStartingMarkerAndPlaceholder`, including an unchanged-port assertion.

Focused verification (all `BUILD SUCCESSFUL`):

```text
./gradlew -p network :runtime:test --tests io.github.developmentnetwork.runtime.ControllerLifecycleTest.managedBackendReclaimsItsDeadStartingMarkerAndPlaceholder --tests io.github.developmentnetwork.runtime.ControllerLifecycleTest.fullModeStartsOnlyItsManagedBackendAndUnexpectedLobbyRestartIsDelayed --tests io.github.developmentnetwork.runtime.RuntimeMainTask6Test
./gradlew -p network test --tests io.github.developmentnetwork.ConsumingProjectIntegrationTest --tests io.github.developmentnetwork.DevNetworkPluginSurfaceTest -x :runtime:test
```

Cross-mode recovery was tightened further: both managed and full adapters inspect both `.starting` and `.full-starting` markers under the registration lock. A dead marker is reclaimable only with a matching owner claim, and stale marker files are removed before the new mode publishes its marker. Added `fullModeReclaimsDeadManagedMarkerBeforeStartingItsPlaceholder` to exercise a stale managed marker through full mode and assert persisted-port preservation. The four-test runtime focus (two recovery tests, full lifecycle, parser) completed with `BUILD SUCCESSFUL`.
