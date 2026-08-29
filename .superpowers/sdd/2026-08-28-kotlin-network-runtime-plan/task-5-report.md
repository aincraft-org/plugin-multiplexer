# Task 5 Implementation Report

## Scope

Implemented the Kotlin runtime controllers, ownership-scoped services, typed command protocol, and focused lifecycle/registration fixtures. Changes are limited to the Task 5 ownership files; the pre-existing README/SKILL/bin and other user changes were not staged.

## Red/green evidence

The required red run was performed immediately after adding the focused fixtures and before Task 5 production classes existed:

```text
./gradlew -p network :runtime:test --tests '*ControllerLifecycleTest' --tests '*RegistrationOwnershipTest'
```

Result: BUILD FAILED during `:runtime:compileTestKotlin` with unresolved Task 5 production packages/types, confirming the fixtures exercised the missing contract.

After implementation, the required focused selectors passed:

```text
./gradlew -p network :runtime:test --tests '*ControllerLifecycleTest' --tests '*RegistrationOwnershipTest'
```

Result: BUILD SUCCESSFUL; 7 focused tests completed with zero failures or errors.

The complete runtime test set was run once after focused tests passed:

```text
./gradlew -p network :runtime:test
```

Result: BUILD SUCCESSFUL; all runtime tests completed with zero failures or errors.

## Implementation

- `RuntimeCommand` now has explicit typed request variants for all nine runtime operations. `RuntimeMain` validates command tokens, setting names, duplicate/missing/malformed values, booleans, integer ports, durations, and maps request errors to exit code 2 and operation errors to exit code 1.
- `InfrastructureController` holds a nonblocking `proxy.lock` for its complete run, generates/preflights Velocity and lobby Paper configuration, starts only proxy/lobby in proxy mode, starts only its requested managed backend in full mode, persists owner/process state, serves authenticated control requests, and restarts an unexpectedly exited lobby after the default two-second delay. Intentional stop suppresses restart.
- `ManagedBackendController` writes managed Paper configuration/ops, registers exactly one managed owner, waits for readiness, and cleans only that owner. It never starts or stops infrastructure.
- Registration/unregistration/reload services use existing registry, port, config, preflight, readiness, and control APIs. External registration validates reachability and immutable forwarding configuration, edits only runtime metadata/config, and rolls back its own claim on a failed final reload. External unregister and stop paths do not touch the external Paper directory/process.
- Stop fallback verifies owner, PID start identity, executable, and working directory before signaling; restart accepts only managed registrations, preserves the persisted port, and affects one backend. Status probes all required endpoints and returns nonzero for failures.
- Pinned Velocity/Paper coordinates and checksums are centralized in `PinnedRuntimeArtifactProvider`; no shell command is invoked by runtime lifecycle code.

## Focused fixture coverage

`ControllerLifecycleTest` uses deterministic Java server fixtures and temporary bases for proxy lock exclusion, proxy-only backend non-start, full-mode one-backend ownership, unexpected lobby restart delay, managed registration cleanup, and protection of unrelated state.

`RegistrationOwnershipTest` covers external untouched/unregister semantics, lobby marker-safe registration, concurrent same-name serialization, idempotent authenticated reload, stop behavior that leaves external registration/Paper untouched, persisted-port retention on restart failure, and deterministic malformed-command exit codes.

## Files

- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/RuntimeMain.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/InfrastructureController.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/RegistrationService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/UnregistrationService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/ReloadService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/StopService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/RestartService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/StatusService.kt`
- `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ControllerLifecycleTest.kt`
- `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RegistrationOwnershipTest.kt`

## Residual risks

- Task 2 `RegistryStore.persistedNamesUnlocked()` treats a live `runtime/lobby.pid` as a possible backend claim. Task 5 services avoid broad persisted scans where possible; external registration temporarily moves only that known infrastructure marker around the locked registry transition and restores it exactly. A future shared change may make the registry itself explicitly exclude lobby infrastructure state.
- The generated runtime request protocol currently expects `--base` and typed settings; Task 6 adapters must serialize these exact keys and supply explicit `server-dir`/owner values for external operations.
- The runtime lifecycle defaults retain Task 3's strict offline preflight behavior; controller callers selecting online proxy mode must ensure the preflight contract and configured mode agree.
- No real Minecraft downloads or server binaries were used in focused tests; artifact fetching remains pinned production behavior and was not network-exercised here.
