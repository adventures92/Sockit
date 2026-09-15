# AGENTS.md

Canonical, vendor-neutral contract for coding agents working in this repository.
`CLAUDE.md` imports this file; do not duplicate policy there.

## What this is

A Kotlin Multiplatform workspace (`rootProject.name = "Sockit"`) whose real deliverable is
`:socketio` — an in-house, coroutine-first **Socket.IO v5 / Engine.IO v4 client** for Android, iOS
and JVM, published as `io.github.adventures92:sockit`.

`:shared`, `:androidApp` and `iosApp` exist only as a demo app **and** as a *consumer encapsulation
gate* proving `:socketio`'s `internal` types cannot leak to consumers.

Deliberately **no** third-party Socket.IO, protocol, or logging libraries — only `kotlinx-*` and
Ktor. The forbidden list (`socket.io-client-java`, `kmp-socketio`, `kmp-xlog`, `kotlinx-datetime`,
Koin/Hilt/Timber/Napier) is a hard architectural constraint, not a preference — see
`docs/architecture.md` §2 and §20. CI enforces it.

## Ground rules

- **Never stage, commit, push, create a PR, tag, or publish without explicit user consent.**
- `:socketio` is the product. Default to `commonMain`; drop into `androidMain` / `iosMain` /
  `jvmMain` only for the `expect`/`actual` platform HTTP client.
- Verify claims against live source and configuration before editing. Do not trust a doc over the
  code it describes — several docs in `docs/` are historical execution logs.
- Report outcomes faithfully. If a gate fails, say so with the output.

## Commands

Gradle wrapper is `./gradlew`. Requires JDK 17+ and the Android SDK (`ANDROID_HOME`, or
`sdk.dir` in a local `local.properties`). There is no lockfile-based install step.

```bash
# Quality gates — run before considering any :socketio change done
./gradlew :socketio:apiCheck spotlessCheck :socketio:detekt

# Auto-fix formatting
./gradlew spotlessApply

# Re-baseline the public API after an INTENTIONAL api/ change (writes JVM + klib dumps)
./gradlew :socketio:apiDump

# Pure/unit + protocol tests (no server needed)
./gradlew :socketio:jvmTest --tests "dev.adven.sockit.protocol.*"

# Full JVM suite — SocketTestServer spawns the Node echo server itself
cd socketio/src/jvmTest/resources && npm ci && cd -
./gradlew :socketio:jvmTest

# Platform smoke (needs simulator / emulator)
./gradlew :socketio:iosSimulatorArm64Test
./gradlew :socketio:connectedAndroidDeviceTest

# Demo apps
./gradlew :androidApp:assembleDebug
# iOS: open iosApp/ in Xcode and run

# API docs — the api/ package only
./gradlew :socketio:dokkaGeneratePublicationHtml   # → socketio/build/dokka/html/

# Server compatibility matrix (optional, socket.io 4.5.4–4.8.1)
cd socketio/src/jvmTest/resources && docker compose -f docker-compose.server-matrix.yml up -d
```

`detekt` runs with `maxIssues: 0` (config in `detekt.yml`) — any finding fails the build.
`spotless` uses ktlint with `android=true`.

> **`dokkaHtml` is a disabled V1 task.** It runs, produces nothing, and reports success. Always use
> `dokkaGeneratePublicationHtml`. Wiring the javadoc jar to the wrong one shipped empty javadoc jars.

> **Do not add an `.editorconfig`.** ktlint reads one if present, and any Kotlin style key in it
> silently overrides the configuration spotless applies in `build.gradle.kts`, reformatting the
> entire codebase. Formatter configuration belongs in `build.gradle.kts`.

## Architecture

### Layering (strict, top calls down only)

Consumers see **only** `dev.adven.sockit.api`. Everything else is `internal` and `explicitApi()` is
on, so accidental public leakage fails compilation. Layers, from public surface down
(`socketio/src/commonMain/kotlin/dev/adven/sockit/`):

| Package | Role |
|---------|------|
| `api/` | **Only public surface.** `SocketClient`, `NamespaceSocket`, `socketOptions{}`/`SocketOptions`, `Transports`, `ConnectionState`, `SocketError`, `SocketException`, `SocketEvent`/`SocketPayload`, `Ack`, `StreamCommand`/`Subscribe`/`Unsubscribe`/`SocketCommand`, `Logger` |
| `socketio/` | `ConnectionManager` (per-origin), `NamespaceSocketImpl`, `SocketClientRegistry` |
| `engineio/` | `EngineConnection`, `UpgradeController`, drain accounting, error mapping |
| `transport/` | `PollingTransport`, `WebSocketTransport`, `WebTransportTransport` (stub), `TransportFactory`, `HttpClientFactory` |
| `protocol/` | In-house `EngineIoCodec`, `SocketIoCodec`, `Packets`, `BinaryAssembler`, `OpenHandshake` |
| `connection/` | `ReconnectPolicy` (exponential backoff + jitter) |
| `internal/` | `WorkQueue`, `EventBus`, `SubscriptionHandle`, `StreamEventNames` (the `"subscribe"`/`"unsubscribe"` constants live here, NOT in the public API) |
| `platform/` | `createPlatformHttpClient` — `expect`/`actual`: OkHttp (Android), Darwin (iOS), CIO (JVM) |

Runtime object graph: `SocketClient` → (registry, keyed by `scheme://host:port` when
`multiplex=true`) → `ConnectionManager` → one `EngineConnection` → many `NamespaceSocketImpl`.
`forceNew=true` bypasses the registry for an independent engine.

### Non-obvious invariants — read before editing engine/transport code

- **Single-worker threading.** All mutable socket/FSM state is mutated on one serial coroutine
  (`WorkQueue`, backed by `Dispatchers.Default.limitedParallelism(1)`). Public API may be called
  from any thread; it enqueues onto the worker. Do not mutate connection state off the worker — it
  reintroduces the upgrade/drain/close races the design exists to prevent.
- **No JDK 21+ collection APIs in `commonMain`.** `List.removeFirst()`/`removeLast()` crash on
  Android below API 35. Use `ArrayDeque.removeFirstOrNull()` or `removeAt(0)`.
- **WebSocket `onOpen` is unconditional.** Once Ktor's `webSocket {}` block is entered, call
  `onOpen()` — never gate it on session subtype or response headers. This is a deliberate fix;
  websocket-only transport depends on it.
- **`emit` vs `emitAwait` semantics.** `emitAwait` completes when the event is *queued* (WorkQueue →
  engine write buffer), not when the transport drains. It throws only on *pre-queue* rejection
  (reserved event name, encode error), as `SocketException.SendFailed`. Async transport write
  failures after enqueue surface on the `errors` Flow as `SocketError.SendFailed` — never silently
  dropped.
- **Public failures are `SocketException`.** The suspending members throw the sealed public
  `SocketException` (`ConnectionFailed` / `SendFailed`), each carrying a `SocketError`. Never throw
  an `internal` exception type across the public boundary — consumers cannot name it in a `catch`.
- **`Subscribe`/`Unsubscribe` event names are library-fixed** and their constants are `internal`.
  Consumers wanting other event names use `SocketCommand` or implement `StreamCommand`.

### Public API dependency scopes

Any dependency whose types appear in `api/` must be declared `api()`, not `implementation()`.
Kotlin/Native exposes everything regardless, so a mistake here only shows up for Android and JVM
consumers — the `consumer-smoke` CI job exists to catch exactly that. Currently `api()`:
`kotlinx-coroutines-core`, `ktor-client-core`, `ktor-client-websockets`, `kotlinx-serialization-json`
(`JsonElement`/`JsonObject`), `kotlinx-io-core` (`ByteString`), and the per-platform Ktor engine.

### Internal logging (compile-time gated)

Full wire/FSM logs are compiled out of release builds. The `generateInternalLogConfig` task in
`socketio/build.gradle.kts` generates `InternalLogConfig.kt` with `INTERNAL_LOG_ENABLED` from the
`socketio.internalLogging` Gradle property, read via `gradle/socketio-dev.local.properties`
(gitignored). Toggle for local dev, then **rebuild** (the flag is compile-time):

```bash
./scripts/toggle-socketio-internal-logging.sh on   # or off / status / (no-arg = flip)
./gradlew :androidApp:installDebug
```

Consumer-facing logging is separate and sanitized: `Logger.essential { ... }` via
`socketOptions { logger = ... }` (no URLs/tokens/payloads, single tag `"SocketIO"`).

## Branching and releases

**Trunk-based. `main` is the only long-lived branch.** There is no `develop`.

| | |
|---|---|
| Default branch | `main` — protected, always releasable |
| Work branches | short-lived `feat/…`, `fix/…`, `docs/…`, `chore/…`, branched from and merged to `main` |
| Merge style | squash, so `main` history is one commit per change |
| Releases | annotated tags `vX.Y.Z` on `main` — never a branch |
| Maintenance | if a `0.x` line ever needs patching after `1.0`, cut `release/0.x` **on demand**; do not keep one standing |

Rationale: `develop` exists to stage a release that ships from a QA'd branch. Here every PR is
gated by CI and every release ships from a tag, so `develop` would add a merge step, a place for
two branches to drift, and a standing trap for outside contributors who fork from `main` and open
the PR against the wrong base. Ktor, Koin, SQLDelight, Okio, Turbine and the `kotlinx.*` libraries
are all trunk-based for the same reason.

Unreleased work accumulates under `## [Unreleased]` in `CHANGELOG.md`; cutting a release means
renaming that heading to `## [X.Y.Z] - YYYY-MM-DD` and tagging. `release.yml` **refuses to publish a
version with no matching CHANGELOG section**, by design.

### Branch policy — rulesets in the repository

The policy lives in `.github/rulesets/*.json` as GitHub **rulesets**, so it is reviewable in a pull
request instead of clicked into the web console. Two ways to apply the same files:

- **Console:** Settings → Rules → Rulesets → New ruleset → **Import a ruleset**, upload the JSON.
- **CLI:** `scripts/setup-github-repo.sh`, which reads the same files through the API.

| File | Target | Effect |
|------|--------|--------|
| `protect-main.json` | `~DEFAULT_BRANCH` | PR required (squash only, threads resolved) · four status checks green and branch up to date · no force-push · no deletion |
| `protect-release-tags.json` | `refs/tags/v*` | a release tag cannot be deleted or moved, so the commit behind a published version stays reproducible |

`bypass_actors` is empty — the policy applies to admins too. See
[`.github/rulesets/README.md`](.github/rulesets/README.md).

### Applied by script

`scripts/setup-github-repo.sh` applies the rulesets plus repository metadata and merge policy. It is
idempotent (a ruleset of the same name is updated, not duplicated), supports `--dry-run` even before
the repository exists, and reads the required-check contexts out of `protect-main.json` and asserts
each one is a real job in `socketio-ci.yml` before applying anything — a required check naming a
job that does not exist is never reported, so the rule waits forever and nothing can ever merge.

```bash
./scripts/setup-github-repo.sh --dry-run   # print every call, change nothing
./scripts/setup-github-repo.sh             # apply
```

Approvals are set to **0** because this is currently single-maintainer: a PR is still required and
CI still has to be green, but nobody is blocked waiting for an approver who does not exist. Raise
`required_approving_review_count` in `protect-main.json` when a second maintainer can review.

It deliberately does **not** touch secrets. Those, plus enabling GitHub Pages and registering the
Central namespace, stay manual — the script prints the list when it finishes.

## CI / CD

Four workflows in `.github/workflows/`. Maven Central is the only publication target.

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `socketio-ci.yml` | PR and push to `main`; manual | The correctness gate. Five jobs (below). |
| `release.yml` | tag `v*`; manual (with `dry_run`) | The only path to a published release. |
| `apk.yml` | push to `main` touching app/library code; manual | Debug demo APK, named by commit, 14-day retention. |
| `docs.yml` | on published release; manual | Dokka HTML → GitHub Pages. |

**Maven Central is the only publication target.** There is no GitHub Packages publication: it would
mean two coordinates for the same library, and it cannot host the Gradle module metadata story as
cleanly for consumers who are not authenticated against GitHub.

### `socketio-ci.yml` jobs

| Job | Runner | What it proves |
|-----|--------|----------------|
| `quality-and-test` | ubuntu | spotless, detekt, apiCheck, Dokka, KMP compile of all targets, `:shared` compile, R8 release smoke, `jvmTest`; then two scans — no `internal` package leaks into `:shared`, and no forbidden dependency appears anywhere in `:socketio`. |
| `server-compatibility` | ubuntu, 5-cell matrix | `ServerCompatibilitySmokeTest` against real `socket.io` **4.5.4 / 4.6.2 / 4.6.2+recovery / 4.7.5 / 4.8.1** servers. Early warning for behavioural drift within protocol v5. |
| `platform-smoke` | macOS | `iosSimulatorArm64Test` — the Darwin engine actually connects. |
| `consumer-smoke` | ubuntu | Publishes `:socketio` to `mavenLocal`, then builds `.github/consumer-smoke/` — a standalone project whose **only** declared dependency is `sockit`, compiling the documented quickstart. Any public type not reachable transitively fails the build. This is the `api()` vs `implementation()` guard. |
| `platform-smoke-android` | macOS, `workflow_dispatch` only | `connectedAndroidDeviceTest` — needs a device or emulator. |

All jobs use `gradle/actions/setup-gradle` (dependency caching + wrapper checksum validation) on
JDK 21, matching the toolchain pinned in `gradle/gradle-daemon-jvm.properties`.

The two scan steps use `grep -rEn`, not `rg` — ripgrep is not guaranteed on GitHub runners, and a
missing binary would make both scans pass vacuously.

### `release.yml` — how a release happens

```
git tag v0.0.1 && git push origin v0.0.1
```

Four jobs, strictly ordered:

1. **`verify`** (macOS) — resolves the version from the tag, rejects anything that is not semver,
   **fails if `CHANGELOG.md` has no `## [<version>]` section**, then runs the static gates, compiles
   all five targets, and runs `jvmTest` + `iosSimulatorArm64Test`. Nothing downstream runs if this
   is red.
2. **`publish`** (macOS — Kotlin/Native iOS targets only build on an Apple host) —
   `publishToMavenCentral` with the version from the tag, signing and Central credentials injected
   as `ORG_GRADLE_PROJECT_*` environment variables. Runs with `--no-configuration-cache` because the
   Dokka javadoc task is not configuration-cache compatible. `automaticRelease = true` means the
   staging repository promotes itself once Central's validation passes — no manual "close and
   release" step in the portal.
3. **`artifacts`** (ubuntu, parallel with publish) — builds the debug demo APK and the Dokka HTML
   bundle, stages them as `sockit-demo-<version>-debug.apk` and `sockit-api-docs-<version>.tar.gz`.
4. **`github-release`** — extracts this version's section from `CHANGELOG.md` with `awk`, appends
   the install snippet, and creates the GitHub Release with both artifacts attached.

`workflow_dispatch` with `dry_run: true` runs `verify` and `artifacts` but skips `publish` — use it
to rehearse a release.

### Release secrets

Five repository secrets, named as in the
[official Kotlin Multiplatform publishing tutorial](https://kotlinlang.org/docs/multiplatform-publish-libraries-to-maven.html).
`release.yml` maps each to the Gradle property the vanniktech plugin reads; nothing else in the
build touches them.

| Secret | Gradle property | Where it comes from |
|--------|-----------------|---------------------|
| `MAVEN_CENTRAL_USERNAME` | `mavenCentralUsername` | central.sonatype.com/usertoken → Generate User Token |
| `MAVEN_CENTRAL_PASSWORD` | `mavenCentralPassword` | same token, password half |
| `GPG_KEY_CONTENTS` | `signingInMemoryKey` | `gpg --armor --export-secret-keys <KEY_ID>` — the whole armored block |
| `SIGNING_PASSWORD` | `signingInMemoryKeyPassword` | the passphrase set when the key was generated |
| `SIGNING_KEY_ID` | `signingInMemoryKeyId` | the **last 8 characters** of the key fingerprint |

The user token is shown once and is not recoverable — regenerate it if lost. The public half of the
key must be uploaded to a keyserver or Central rejects the signature:

```bash
gpg --full-generate-key                                   # ECC / Curve 25519, no expiry
gpg --list-keys                                           # note the fingerprint
gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
gpg --armor --export-secret-keys <FINGERPRINT> > key.gpg  # -> GPG_KEY_CONTENTS
```

### Publishing prerequisites, in order

1. **Verified namespace** on Central for `io.github.adventures92` — created by proving ownership of
   the matching GitHub account, no DNS record required.
2. **PGP key** generated, public half on a keyserver, private half in `GPG_KEY_CONTENTS`.
3. **User token** generated and stored.
4. **Version must not end in `-SNAPSHOT`** — Central rejects it.
5. **POM must carry** name, description, url, licence, developer (id, name, email, organization,
   organizationUrl) and SCM. The Kotlin Gradle plugin's `checkPomFileFor<Publication>Publication`
   tasks enforce this and run in `release.yml`'s `verify` job.
6. **Publish from a single host.** Central forbids duplicate publications, so all five modules are
   published by one job; `release.yml` uses one macOS runner for exactly this reason.

### Why APKs are debug-only

`androidApp` declares no `signingConfig`, so `assembleRelease` produces an APK nobody can install.
To ship signed release builds, add a `signingConfig` fed from `KEYSTORE_BASE64` /
`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` secrets first; `apk.yml` carries the note.

## What gets published

One Gradle publish produces five Maven modules; Gradle module metadata routes consumers to the
right one, so a KMP consumer writes a single dependency line in `commonMain`.

| Module | Artifact | For |
|--------|----------|-----|
| `sockit` | `.jar` + `.module` + `-kotlin-tooling-metadata.json` | KMP metadata — what `commonMain` resolves |
| `sockit-android` | `.aar` (with `consumer-rules.pro`) | Android |
| `sockit-iosarm64` | `.klib` | iOS device |
| `sockit-iossimulatorarm64` | `.klib` | iOS simulator (Apple silicon) |
| `sockit-jvm` | `.jar` | JVM |

Every module also carries `-sources.jar` and `-javadoc.jar` (Dokka HTML), both required by Maven
Central. Verify the whole set locally before a release:

```bash
./gradlew :socketio:publishToMavenLocal -PVERSION_NAME=0.0.0-local --no-configuration-cache
find ~/.m2/repository/io/github/adventures92 -type f | sort
```

There is no `iosX64` target — Intel Mac simulators are not supported. Adding a target is additive
and safe; **removing** a published one breaks consumers, so treat the target list as a commitment.

## Discoverability — klibs.io

[klibs.io](https://klibs.io) is JetBrains' search index for Kotlin Multiplatform libraries. Listing
is automatic; the [FAQ](https://klibs.io/faq) states four conditions, all of which this project
already satisfies once it is public and published:

| Condition | Status |
|-----------|--------|
| Open source on GitHub | after the repository is made public |
| At least one artifact on Maven Central | after the first `v*` tag |
| A multiplatform artifact carrying `kotlin-tooling-metadata.json` | ✅ the root `sockit` publication produces it |
| A POM with a valid GitHub link in `url` or `scm.url` | ✅ both point at `github.com/adventures92/Sockit` |

Indexing follows Maven Central's public index, so a new library appears **within about a month**;
subsequent versions appear the next day. To skip the wait, file an index request:
<https://github.com/JetBrains/klibs-io-issue-management/issues/new?labels=index-request&template=index_request.yml>

Nothing in the build needs to change for this — the requirement is a side effect of publishing a
correct KMP POM, which `checkPomFileFor<Publication>Publication` already enforces.

## Agent tooling

`.agents/` is the vendor-neutral home for agent skills, alongside this file. `.claude/skills` is a
**committed symlink** to `.agents/skills` — git stores it as mode `120000`, so it resolves on clone
with no bootstrap script and no git hook (hooks live in `.git/hooks`, which git does not
distribute). See [`.agents/README.md`](.agents/README.md).

| Skill | Purpose |
|-------|---------|
| [`release`](.agents/skills/release/SKILL.md) | Cut a release — pre-flight, tag, and verify the artifact landed on Maven Central |

Skills are an **authoring aid, never a pipeline dependency**. No workflow may require an agent to
run: a release must be reproducible by anyone holding the secrets, so a fork with no agent access
still publishes correctly. Generated release notes are excluded for the same reason — the
`CHANGELOG.md` entry is curated per pull request and is what `release.yml` extracts.

## Contributor-facing configuration

| File | Purpose |
|------|---------|
| `.github/PULL_REQUEST_TEMPLATE.md` | Checklist gated on what the change touches — public `api/` changes require `apiDump` and KDoc; protocol changes require a conformance vector |
| `.github/ISSUE_TEMPLATE/bug_report.yml` | Structured form: version, platform, transport, server version, observed `SocketError`. Blank issues are disabled |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | Requires confirming the request needs no forbidden dependency |
| `.github/ISSUE_TEMPLATE/config.yml` | Routes questions to Discussions and security reports to a private advisory |
| `.github/labels.json` | The label set, applied by `setup-github-repo.sh`. JSON, not YAML — a YAML loader coerces hex colours like `5319e7` into scientific notation |

## Working in this repo

- **Any change to the public `api/` package** requires a matching `./gradlew :socketio:apiDump`
  (Binary Compatibility Validator, JVM **and** klib dumps) and Dokka-visible KDoc. `apiCheck` in CI
  fails otherwise.
- **Protocol changes** need a vector in `ProtocolConformanceTest`, copied from the upstream
  [`socket.io-protocol`](https://github.com/socketio/socket.io-protocol) test suite.
- The `docs/` directory is the design system of record — `architecture.md` (HLD),
  `implementation-plan.md` (LLD), `socketio-conformance-and-maintenance.md` (spec conformance +
  maintenance strategy), and the gap/hardening plans. Consult it for the *why* behind constraints
  before proposing changes that touch threading, the error model, transports, or the public surface.
  Note that `execution-state.md` and the gap plans are historical execution logs, not current truth.
- The bundled Node echo server (`socketio/src/jvmTest/resources/socket-server.js`) is original to
  this repository and implements only the handlers the Kotlin suites exercise. Adding a test that
  needs a new server behaviour means adding the handler there too.
