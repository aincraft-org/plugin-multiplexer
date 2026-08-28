# plugin-multiplexer

[![Build](https://img.shields.io/github/actions/workflow/status/aincraft-org/plugin-multiplexer/ci.yml?branch=main&label=build)](https://github.com/aincraft-org/plugin-multiplexer/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/aincraft-org/plugin-multiplexer)](https://github.com/aincraft-org/plugin-multiplexer/releases/latest)
[![Nightly](https://img.shields.io/github/v/release/aincraft-org/plugin-multiplexer?include_prereleases&filter=nightly&label=nightly)](https://github.com/aincraft-org/plugin-multiplexer/releases/tag/nightly)
![Java](https://img.shields.io/badge/Java-25-orange)
![Velocity](https://img.shields.io/badge/Velocity-4.1.1-blue)
![Paper](https://img.shields.io/badge/Paper-26.2-blue)

A shared local development network for one Velocity proxy, a continuously supervised lobby, and multiple isolated Paper backends. The repository also contains the Proxy Inspector Velocity plugin, which lists registered servers and loaded proxy plugins.

## Components

- `network/` — Gradle plugin `io.github.development-network`.
- `velocity-plugin/` — Proxy Inspector for Velocity 4.1.1.
- `bin/` — proxy, lobby, backend, registration, reload, and status scripts.
- `SKILL.md` — agent-facing network and ownership contract.

## Requirements

- Java 25
- Gradle 9.7.1 (the root wrapper is committed)
- `bash`, `curl`, `sha256sum`, `python3`, and `shellcheck` for the full quality gate

## Build and quality checks

```bash
./gradlew clean check
./gradlew assemble
shellcheck -x -P bin bin/*.sh
```

The root Gradle build orchestrates both Gradle modules, verifies the generated Velocity plugin metadata, and forwards `-PbuildVersion` to nested builds. Local builds use a dated `-SNAPSHOT` version; CI and releases use `YYYY.MM.DD.<github_run_number>`.

Artifacts are written to:

```text
network/build/libs/development-network-plugin-<version>.jar
velocity-plugin/build/libs/proxy-inspector-<version>.jar
```

## Proxy Inspector

Copy the built proxy plugin into the Velocity proxy's `plugins/` directory:

```bash
cp velocity-plugin/build/libs/proxy-inspector-*.jar /path/to/velocity/plugins/
```

Commands:

- `/servers` or `/serverlist` — asynchronously pings every registered server, then reports online/offline counts, names in each group, endpoints, and player counts.
- `/plugins` or `/pluginlist` — plugins loaded by the Velocity proxy, with IDs and versions.

When the proxy starts with `DEV_USERS='name'`, Proxy Inspector grants those usernames the explicit Velocity
admin nodes `velocity.command.*`, `velocity.command.info`, `velocity.command.plugins`, `velocity.command.reload`,
`velocity.command.dump`, `velocity.command.heap`, `velocity.command.glist`, and `velocity.command.send`.
Managed Paper backends receive the same users through `ops.json`. External Paper servers are not modified; run
`/op name` or configure their permission plugin separately. This grant is for the local development harness only.

The plugin does not inspect Paper backend plugin directories. That requires a backend-side reporting plugin and protocol.

## Shared deployment model

Start the shared infrastructure once:

```bash
./gradlew runProxy -PnetworkBase=/shared/network
```

For an authenticated proxy (Paper backends remain offline for modern forwarding):

```bash
./gradlew runProxy \
  -PnetworkBase=/shared/network \
  -PnetworkOnlineMode=true
```

For a backend that this repository manages:

```bash
./gradlew runBackend \
  -PnetworkBase=/shared/network \
  -PnetworkBackend=my-plugin
```

For a Paper server already started by the plugin project (`runServer`), attach it without transferring lifecycle ownership:

```bash
./gradlew registerBackend \
  -PnetworkBase=/shared/network \
  -PnetworkBackend=my-plugin \
  -PnetworkBackendPort=30070 \
  -PnetworkRegistrationOwner=my-plugin-agent
```

`registerBackend` never starts, stops, or deploys to Paper. `runBackend` is the managed alternative. `runNetwork` remains a one-project full-stack convenience task and should not be used by multiple projects sharing one network base.

## Server development skills

This repository is the standalone `development-network` implementation consumed by [server-development-skills](https://github.com/aincraft-org/server-development-skills). CI checks the relevant upstream skill contract at pinned revision `360a28e63c924a881cb9d6c22e66a2d910104f59` with nested submodules disabled. The upstream repository's `development-network` submodule points back to this repository, so vendoring the entire upstream repository here would create a circular submodule graph.

## CI and releases

- `ci.yml` runs the root Gradle quality gate, packages both artifacts, runs Bash syntax checks, and runs ShellCheck.
- `nightly.yml` replaces the rolling `nightly` prerelease every day and is manually dispatchable.
- `release.yml` publishes stable artifacts for CalVer tags or a manually supplied CalVer version.

See `SKILL.md` for the complete runtime registration contract and operational commands.
