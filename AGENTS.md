# Agent Instructions

## Purpose

This repository is a development-only Velocity/Paper network multiplexer. It gives a developer one client connection to the shared Velocity proxy and lets them move between isolated Paper servers by name instead of tracking backend IPs, changing ports, or reconnecting:

```text
Minecraft client -> localhost:25565 (Velocity proxy)
                         |- lobby:30066
                         `- named Paper backends:30067+
```

The two independently driven surfaces are:

- **Proxy/infrastructure:** one shared Velocity proxy and its lobby.
- **Backends:** independently managed or already-running Paper servers registered with that proxy.

The proxy routes traffic; the network harness owns server processes. The built-in Velocity `/server <name>` command performs the switch. Do not implement client-side IP or port tracking.

## Read first

- `README.md` is the concise user-facing setup and command reference.
- `SKILL.md` is the authoritative runtime contract, ownership matrix, port allocation rules, forwarding requirements, and troubleshooting guide. Read it before changing lifecycle, registration, configuration generation, or startup behavior.
- `build.gradle.kts`, `network/build.gradle.kts`, and `velocity-plugin/build.gradle.kts` define the build boundaries and pinned toolchain expectations.

If this file and the detailed runtime contract appear inconsistent, preserve the behavior in the source and update the documentation that is stale. Do not silently invent a second contract.

## Repository map

- `network/` — Gradle plugin `io.github.development-network`; exposes `runProxy`, `runBackend`, `registerBackend`, `unregisterBackend`, and the single-project `runNetwork` convenience task.
- `velocity-plugin/` — standalone Velocity plugin `proxy-inspector`; provides `/servers` and `/plugins` (with their aliases) and optional development admin permissions. It does not replace or alter Velocity's built-in `/server` command.
- `network/runtime/` — embedded Kotlin runtime for process lifecycle, configuration generation, registration, probing, and artifact management.
- `runtime/`, `binaries/`, and `logs/` — generated or runtime state; they are ignored and are not source-of-truth files.

Keep changes in the layer that owns the behavior. Change Gradle task wiring in `network/`, proxy inspection behavior in `velocity-plugin/`, and process/configuration lifecycle in `network/runtime/`.

The runtime Gradle task examples below are run from a consuming project that applies `io.github.development-network` (usually through `includeBuild("/path/to/plugin-multiplexer/network")`). This standalone repository's root wrapper builds and verifies the modules; it does not apply the network plugin or expose `runProxy`/`runBackend` itself. From this repository, use `./gradlew check` or `./gradlew assemble`; run network tasks from a consuming project.


## Shared-network ownership

A `networkBase` directory is one coordination domain. It has one infrastructure owner and zero or more backend owners:

- `runProxy` owns starting and stopping the shared proxy and lobby.
- `runBackend` owns exactly one managed Paper backend, its registration, and its Paper process. It must not start, restart, or stop the proxy or lobby.
- `registerBackend` attaches an already-running external Paper server. It verifies and registers it but never builds, starts, stops, deploys to, or rewrites that server.
- `unregisterBackend` removes only the caller's registration. External Paper processes remain the responsibility of their original project/process.
- `runNetwork` is a one-project full-stack convenience task, not the coordination primitive for multiple projects sharing one network.
- `runtime/register.lock` serializes registry edits, port/owner metadata, config regeneration, and live reload. Backend names must be unique; a second owner must fail rather than replace an existing registration.

Use a stable registration owner token and the matching token for cleanup. Use the documented force operation only for network-owner cleanup. Never take ownership of another agent's backend by deleting its state or killing its process.

## Development rules

1. **Use names, not addresses.** Backend names must match `[A-Za-z0-9_-]+`. Route through the registry and generated `velocity.toml`; do not hardcode backend IPs or duplicate port math in a new component.
2. **Use the existing lifecycle entry points.** Gradle tasks are the only supported public entry points. The embedded Kotlin runtime keeps initial configuration generation and live reload on the same implementation path.
3. **Respect persisted ports.** `runtime/<name>.port` keeps a live backend stable across registry reindexing. Do not delete or rewrite it casually. Registration and automatic allocation must continue to honor occupied and reserved ports.
4. **Preserve forwarding invariants.** Modern Velocity forwarding is required with BungeeCord forwarding disabled on Paper. Before local startup or client connection, perform the forwarding/authentication preflight described in `SKILL.md`: verify the effective proxy authentication mode (online by default or explicitly offline), keep every Paper `server.properties` in offline mode, and make each Paper `proxies.velocity.online-mode` match the proxy. Never assume the proxy setting configures Paper. Do not change an external proxy or external backend when its mode/configuration is unknown; stop and ask instead.
5. **Keep external servers external.** The external registration path may verify reachability and forwarding configuration, but must not edit the external server's files or grant it operators. External lifecycle and permissions stay with its owning project.
6. **Keep development grants scoped.** `DEV_USERS` and the Proxy Inspector admin permissions are for the local development harness only. Do not broaden them into a production/shared-proxy authorization mechanism.
7. **Preserve pinned artifacts.** Velocity/Paper versions and SHA-256 pins are coordinated by the runtime artifact provider. If a pin changes, update the version, build, checksum, tests, and documented forwarding compatibility together.
8. **Stop safely.** Use `stopNetwork` or the owning long-lived Gradle task. Never use broad process matching or manual termination of unrelated Java processes; Paper needs its shutdown hooks to save worlds.
9. **Do not treat generated state as source.** Inspect runtime logs and generated configs when diagnosing a live network, but make permanent fixes in Kotlin runtime, plugin, or build source.
10. **Avoid unrelated refactors.** Preserve the existing Gradle composite-build and upstream `server-development-skills` contract unless the task explicitly changes it.

## Common workflows

Start shared infrastructure once, then give each project one backend ownership mode:

```bash
# Shared proxy and lobby
./gradlew runProxy -PnetworkBase=/shared/network

# Managed backend: this project builds, starts, and owns Paper
./gradlew runBackend \
  -PnetworkBase=/shared/network \
  -PnetworkBackend=my-plugin

# External backend: another process already owns Paper
./gradlew registerBackend \
  -PnetworkBase=/shared/network \
  -PnetworkBackend=my-plugin \
  -PnetworkBackendPort=30070 \
  -PnetworkRegistrationOwner=my-plugin-agent
```

After the preflight passes, connect the client to `localhost:25565`, land in the lobby, and use `/server my-plugin` to switch backends. For hot-add, hot-remove, or recovery, use `registerBackend`, `unregisterBackend`, and `reloadNetwork` according to `SKILL.md`.

Use `networkStatus` for endpoint reachability and `stopNetwork` for controlled shutdown. The status probe does **not** prove proxy routing: verify routing with a real client login followed by `/server <name>`.

## Validation

Run the smallest relevant checks while iterating. Before completing source changes, use the repository quality gate as applicable:

```bash
./gradlew clean check
./gradlew assemble
```

For lifecycle or routing changes, also exercise the real network path: start the owned components, inspect readiness/logs, run `networkStatus`, connect to `localhost:25565`, and switch between at least two named backends when available. Confirm that an external backend remains running after unregister/stop operations.

A change is complete only when it preserves the one-connection developer workflow, the ownership boundary, safe port/registry behavior, and the documented proxy-to-backend routing semantics. Update `README.md` or `SKILL.md` when user-visible commands or runtime contracts change; do not duplicate their full version tables or operational matrix here.
