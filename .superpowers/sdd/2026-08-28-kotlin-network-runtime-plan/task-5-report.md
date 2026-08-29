# Task 5 Implementation Report

## Scope

Implemented the Kotlin runtime controllers, ownership-scoped services, typed command protocol, and focused lifecycle/registration fixtures. The review-fix integration also hardens shared registry transitions and process-output/termination handling required by the controllers. Pre-existing README/SKILL/bin and other user changes were not staged.

## Red/green evidence

The required red run was performed immediately after adding the focused fixtures and before Task 5 production classes existed:

```text
./gradlew -p network :runtime:test --tests '*ControllerLifecycleTest' --tests '*RegistrationOwnershipTest'
```

Result: BUILD FAILED during `:runtime:compileTestKotlin` with unresolved Task 5 production packages/types, confirming the fixtures exercised the missing contract.

Baseline after the initial Task 5 implementation, before review-fix integration:

```text
./gradlew -p network :runtime:test --tests '*ControllerLifecycleTest' --tests '*RegistrationOwnershipTest'
```

Result: BUILD SUCCESSFUL; 7 focused tests completed with zero failures or errors.

The initial implementation's complete runtime test set also passed before review-fix integration. Its result is retained as historical evidence only; the final-source verification appears below.

```text
./gradlew -p network :runtime:test
```

Result: BUILD SUCCESSFUL; all runtime tests completed with zero failures or errors at the pre-fix baseline.


## Implementation

- `RuntimeCommand` has explicit typed request variants for all nine runtime operations. `RuntimeMain` validates per-command settings, rejects malformed values/names/map options with request exit code 2, supports proxy port zero allocation, defaults development mode offline, and maps operation failures to exit code 1.
- `InfrastructureController` holds the proxy lock for its complete run, resolves/persists local ports, generates/preflights Velocity and Paper configuration, writes lobby operators, starts only owned components, retains process handles before failure points, ties readiness to live identities, redirects child output, persists canonical process state, serves authenticated reload/shutdown, monitors proxy/lobby ownership, restarts unexpected lobby exits after two seconds, and keeps the control lease through cleanup.
- `ManagedBackendController` reserves one managed claim before launch, writes Paper configuration/ops, registers exactly one identity, waits for identity-bound readiness, wakes on stop, reports unexpected exits as failures, and removes state only after proven termination. It never starts or stops infrastructure.
- Registration/unregistration/reload transitions use the shared registration lock and preserve effective proxy bind/target/online settings. Infrastructure markers are excluded by `RegistryStore`; external registration validates reachability/forwarding without editing external files or processes and rolls back only its own state.
- Stop fallback requires a non-live controller lease, complete owner/start/executable/cwd identity, and proven termination. Restart is serialized, preflights before replacement, retains the persisted port/work directory, verifies replacement identity around readiness, and rolls back failed replacements without touching external claims. Status probes every required endpoint.
- Pinned Velocity/Paper coordinates and checksums remain centralized in `PinnedRuntimeArtifactProvider`; runtime lifecycle code invokes no shell command.
## Focused fixture coverage

`ControllerLifecycleTest` uses deterministic Java server fixtures and temporary bases for proxy lock exclusion, proxy-only backend non-start, full-mode one-backend ownership, unexpected lobby restart delay, managed registration cleanup, and protection of unrelated state.

`RegistrationOwnershipTest` covers external untouched/unregister semantics, lobby marker-safe registration, concurrent same-name serialization, idempotent authenticated reload, stop behavior that leaves external registration/Paper untouched, persisted-port retention on restart failure, and deterministic malformed-command exit codes.

## Review-fix integration

The integrated review fixes removed the lobby marker move/restore workaround, added a shared locked registry transition, hardened parser/service settings and ownership checks, and repaired stop/restart rollback and persisted-state handling. Infrastructure startup now retains children before readiness, binds readiness to their identities, redirects child output, preserves the control lease through cleanup, sends the live proxy reload command, and reports termination failure without deleting unproven ownership state. The dead-root/reparented-child regression exposed during the final suite was corrected conservatively to return `NOT_OWNED`.

Final focused verification after all integrated edits:

```text
./gradlew -p network :runtime:test --tests '*ControllerLifecycleTest' --tests '*RegistrationOwnershipTest'
```

Result: BUILD SUCCESSFUL; 7 focused tests completed with zero failures or errors.

Final complete runtime verification after the dead-root correction:

```text
./gradlew -p network :runtime:test
```

Result: BUILD SUCCESSFUL; 82 runtime tests completed with zero failures or errors.

## Files

- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/RuntimeMain.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/InfrastructureController.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/process/ProcessSupervisor.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/registry/RegistryStore.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/RegistrationService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/UnregistrationService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/ReloadService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/StopService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/RestartService.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/service/StatusService.kt`
- `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ControllerLifecycleTest.kt`
- `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/RegistrationOwnershipTest.kt`

## Residual risks

- Port-zero allocation selects an available port before child bind; another process can claim it during the handoff, so readiness/identity checks fail closed rather than proving the wrong process ready.
- Lobby map publication retains Task 3's Linux/default-provider Foreign Function & Memory `renameat2(RENAME_NOREPLACE)` capability requirement and fails closed on unsupported runtimes/providers.
- No real Minecraft downloads or server binaries were used in focused tests; artifact fetching remains pinned production behavior and was not network-exercised here.
- Task 6 adapters must serialize the typed `--base`/request settings and supply explicit external `server-dir`, owner, and effective proxy settings.
