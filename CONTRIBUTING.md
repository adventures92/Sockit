# Contributing

Thanks for taking a look. Issues and pull requests are both welcome.

## Branching

**Trunk-based — `main` is the only long-lived branch.** There is no `develop`.

```bash
git switch -c feat/binary-ack-ids main
# … work, then open a PR against main
```

Branch names: `feat/`, `fix/`, `docs/`, `chore/`. PRs squash-merge, so `main` keeps one commit per
change. Releases ship from annotated `vX.Y.Z` tags on `main`, never from a branch.

Add your entry under `## [Unreleased]` in `CHANGELOG.md` as part of the PR — a release is cut by
renaming that heading, and `release.yml` refuses to publish a version with no CHANGELOG section.

## Ground rules

**`:socketio` is the product.** `shared`, `androidApp` and `iosApp` are a demo — and the gate that
proves the library's `internal` types cannot leak to consumers. Changes belong in `commonMain`
unless they genuinely need a platform `actual`.

**No new runtime dependencies.** The library depends on `kotlinx-*` and Ktor, and nothing else.
Third-party Socket.IO, protocol and logging libraries are excluded by design, not by accident —
see [`docs/architecture.md`](docs/architecture.md) §2 and §20. CI enforces this.

**Mutable state lives on one worker.** All socket and FSM state is mutated on a single serial
coroutine (`WorkQueue`). The public API may be called from any thread and enqueues onto that
worker. Mutating connection state off the worker reintroduces the upgrade/drain/close races the
design exists to prevent.

**No JDK 21+ collection APIs in `commonMain`.** `List.removeFirst()` / `removeLast()` crash on
Android below API 35. Use `ArrayDeque.removeFirstOrNull()` or `removeAt(0)`.

## Before you open a pull request

```bash
./gradlew spotlessApply                                  # format
./gradlew :socketio:apiCheck spotlessCheck :socketio:detekt
./gradlew :socketio:compileAndroidMain \
  :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
./gradlew :socketio:jvmTest
```

`detekt` runs at `maxIssues: 0` — any finding fails the build. `jvmTest` spawns the bundled Node
echo server itself; run `npm ci` in `socketio/src/jvmTest/resources` once beforehand.

> The repository deliberately has **no `.editorconfig`**. ktlint reads one if present, and any
> Kotlin style key in it silently overrides the configuration spotless applies in
> `build.gradle.kts` — reformatting the whole codebase. Configure the formatter there, not in an
> editor config file.

**Changing anything in `api/`?** Run `./gradlew :socketio:apiDump` and commit the updated
`socketio/api/` files, and add KDoc — only that package is documented by Dokka. Adding or changing
a public type is a deliberate act: it becomes a compatibility commitment.

**Protocol changes** need a vector in `ProtocolConformanceTest`, copied from the upstream
[`socket.io-protocol`](https://github.com/socketio/socket.io-protocol) test suite.

## CI

| Job | When | What |
|-----|------|------|
| `quality-and-test` | every PR | spotless, detekt, apiCheck, Dokka, KMP compile, R8 smoke, `jvmTest` |
| `server-compatibility` | every PR | `socket.io` 4.5.4 – 4.8.1 matrix + recovery-enabled 4.6.2 |
| `platform-smoke` | every PR | iOS `iosSimulatorArm64Test` |
| `consumer-smoke` | every PR | resolves the published artifact from `mavenLocal` and compiles the README quickstart — the guard against declaring a public-API dependency as `implementation` |
| `platform-smoke-android` | manual | `connectedAndroidDeviceTest` (needs a device or emulator) |

## Repository settings

GitHub-side configuration — merge policy, `main` protection, release-tag protection — lives in
`scripts/setup-github-repo.sh`, not in the web UI. Change the script and open a PR; run
`./scripts/setup-github-repo.sh --dry-run` to see exactly what it would do.

## Releasing

Maintainers only. Update `CHANGELOG.md` with a `## [x.y.z]` section, then push the tag:

```bash
git tag v0.0.1 && git push origin v0.0.1
```

`release.yml` re-runs the full gate, publishes to Maven Central, and creates the GitHub Release
with the demo APK and API docs attached. The tag never publishes a red build. Maven Central is the
only publication target.
