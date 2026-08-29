# Task 7 report: Documentation, CI, and shell CLI removal

## Result

Task 7 is complete in the continuation worktree. User-facing documentation now describes the Gradle plugin and embedded runtime as the only supported interface. The obsolete tracked shell entry points are removed, CI retains Gradle quality and artifact gates, and the documentation contract test scans README, SKILL, an optional root AGENTS.md, and every YAML workflow file.

## TDD evidence

- RED evidence was captured in the prior Task 7 run/history before the documentation rewrite: `./gradlew -p network :test --tests io.github.developmentnetwork.DocumentationContractTest --console=plain` exited nonzero against the old shell-oriented documentation because the forbidden shell references and required Gradle runtime contracts were not yet satisfied.
- GREEN command, run from the repository root after the final edits:

  ```text
  ./gradlew -p network :test --tests io.github.developmentnetwork.DocumentationContractTest --console=plain
  ```

  Actual result: `BUILD SUCCESSFUL in 1s` (9 actionable tasks: 2 executed, 7 up-to-date).
- Relevant network-module tests:

  ```text
  ./gradlew -p network :test --tests io.github.developmentnetwork.DevNetworkPluginSurfaceTest --tests io.github.developmentnetwork.ConsumingProjectIntegrationTest --tests io.github.developmentnetwork.RuntimePackagingTest --console=plain
  ```

  Actual result: `BUILD SUCCESSFUL in 9s` (9 actionable tasks: 1 executed, 8 up-to-date).

No formatters, linters, ShellCheck, or project-wide/full-suite checks were run.

## Files and scope

- `README.md`: Gradle-only consumer setup, embedded runtime extraction and `${java.home}/bin/java` launch wording, all nine tasks, properties/defaults, ownership boundaries, preflight/forwarding, ports, maps/archive safety, status caveats, artifact pins, and agent split.
- `SKILL.md`: matching Gradle-only operational contract, composite-build setup, task/property reference, ownership and external-server safety, preflight/forwarding, artifact/map/archive safety, status/routing, and agent split.
- `.github/workflows/ci.yml`: removed shell quality and script-packaging references while retaining Java setup, Gradle quality, assembly, and JAR artifact upload gates. `nightly.yml` and `release.yml` required no change; their remaining shell blocks are the requested version/GitHub CLI release logic.
- `network/src/test/kotlin/io/github/developmentnetwork/DocumentationContractTest.kt`: deterministic corpus scan, recursive `.yml`/`.yaml` workflow discovery, forbidden legacy command/path checks, nine-task/include-build checks, and retained safety/ownership/artifact/property assertions. `${java.home}/bin/java` is normalized to `<java-launcher>` before forbidden-token checks.
- `network/build.gradle.kts`: deterministic test working directory (`project.projectDir`) retained so the root-invoked focused command resolves documentation paths consistently.
- Deleted exactly the 15 listed tracked shell files present in the base worktree: `boot-backend.sh`, `boot-external.sh`, `boot-lobby.sh`, `boot-proxy.sh`, `dev-network-status.sh`, `dev-network.sh`, `fetch-jar.sh`, `register-backend.sh`, `reload-network.sh`, `restart-backend.sh`, `stop-dev-network.sh`, `test-network.sh`, `unregister-backend.sh`, `velocity-toml.sh`, and `write-ops.sh`. The listed `install-lobby-map.sh` and `test-lobby-map.sh` were already absent in the base and were not recreated or otherwise touched. No generated runtime directories, lobby-map Worker, or unrelated files were deleted. No tracked `AGENTS.md` exists.

## Self-review and concerns

- YAML syntax validation with PyYAML passed: `parsed ci.yml, nightly.yml, release.yml`.
- Operational deleted-path search across Kotlin, README/SKILL, workflows, and tests returned no references. The contract test necessarily contains the forbidden token list, and the authoritative historical design/plan documents retain the migration table; neither is an operational reference.
- `git diff --check` passed.
- The remaining `shellcheck` comments in the Gradle wrapper are implementation comments in the standard wrapper, not CI jobs or the removed shell interface; they are outside Task 7's requested CI/documentation scope.
- Untracked pre-existing SDD progress/review artifacts and task briefs were left untouched and are not part of this Task 7 change; only this report is added as the requested deliverable.
