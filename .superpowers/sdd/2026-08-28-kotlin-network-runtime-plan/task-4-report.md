# Task 4 Implementation Report

## Scope

Implemented Task 4 only: process identity capture and owner-verified supervision, bounded TCP readiness probing, and authenticated Unix-domain controller control. Controller orchestration, RuntimeMain dispatch, and Gradle adapters remain deferred to Tasks 5–6.

## Red-Green Evidence

Required red run after writing the two focused test classes and before production implementation:

```text
./gradlew -p network :runtime:test --tests '*ProcessSupervisorTest' --tests '*ControlChannelTest'
```

Result: `BUILD FAILED` during `:runtime:compileTestKotlin` with unresolved Task 4 production packages/types, confirming the new tests were exercising the missing contract rather than existing behavior.

Focused verification after implementation:

```text
./gradlew -p network :runtime:test --tests '*ProcessSupervisorTest' --tests '*ControlChannelTest'
```

Result: `BUILD SUCCESSFUL`; 11 focused tests completed with zero failures or errors (7 process/supervision/readiness tests and 4 control-channel tests).

The tests cover direct Java launch and captured PID/start/executable/working-directory identity, changed/reused PID and external state non-interference, graceful termination, descendant termination, deadline force escalation, interrupt-status restoration, TCP readiness success/timeout, random token generation, owner-only state permissions where POSIX is available, authenticated reload/shutdown, wrong-token rejection, stale lease failure, and stale socket handling.

No formatter, linter, project-wide build, or project-wide test command was run.

## Implementation

- `ProcessIdentityReader` captures `ProcessHandle` PID/start instant/command and the expected working directory. Matching fails closed when start identity is unavailable or differs, and verifies Linux `/proc/<pid>/cwd` where available.
- `ProcessSupervisor` launches with `ProcessBuilder` directly (no shell/wrapper), retains the process stdin channel, waits interruptibly while restoring the interrupt status, and terminates descendants and the owned root gracefully before deadline-bounded force escalation. Every signal is gated by the captured process start identity, executable, and working directory.
- `ReadinessProbe` retries bounded TCP connects until a `Duration` deadline and reports `TimeoutException` on failure.
- `ControlServer` binds a Unix-domain socket, writes a 256-bit URL-safe `SecureRandom` token and a PID/start-identity controller lease using owner-only temporary files and atomic moves, and accepts only authenticated `reload`/`shutdown` commands. Stale socket removal is restricted to dead recorded leases or unleased paths proven to have no listener.
- `ControlClient` validates token and live matching lease state before connecting and uses selector-bounded Unix-socket I/O.

## Files

Created in Task 4 ownership:

- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/process/ProcessIdentityReader.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/process/ProcessSupervisor.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/process/ReadinessProbe.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/ControlProtocol.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/ControlServer.kt`
- `network/runtime/src/main/kotlin/io/github/developmentnetwork/runtime/controller/ControlClient.kt`
- `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ProcessSupervisorTest.kt`
- `network/runtime/src/test/kotlin/io/github/developmentnetwork/runtime/ControlChannelTest.kt`

## Risks and Follow-up

- Java's portable `ProcessHandle.Info` does not expose a working directory; Linux `/proc` verification is used when available, while other platforms retain the expected directory and fail closed when start identity is unavailable.
- The control lease is stored as `proxy.control.lease` beside the specified `proxy.control.token`; Task 5 controllers must use `ControlServer.generateToken()` (or provide an equivalent cryptographically random token) and preserve both state files for the server lifetime.
- A controller that cannot obtain process start identity cannot serve control requests or be signalled, which is intentional fail-closed behavior.
- The test fixture uses a JVM shutdown hook only to create an unresponsive graceful-termination scenario; production ownership and cleanup do not use `Runtime.addShutdownHook`.
