# Publishing Plan — `:socketio` as a public Kotlin Multiplatform library

> **Status:** ✅ executed — decisions D1–D5 settled, Phases A–C implemented (2026-09-14)
> **Goal:** publish `:socketio` (Android, iOS arm64 + simulator, JVM) to Maven Central from a public repo, consumable without the demo modules.
> **Companion docs:** [`architecture.md`](architecture.md), [`socketio-conformance-and-maintenance.md`](socketio-conformance-and-maintenance.md), [`CLAUDE.md`](../CLAUDE.md)

## 0. Where we already are (the hard parts, done)

Publishing turns the public API into a contract. This repo is already disciplined for that:

- `explicitApi()` is on — accidental public leakage fails compilation.
- Binary Compatibility Validator (`apiCheck`/`apiDump`) guards the surface every change.
- `:socketio` is self-contained; `internal` types are encapsulation-gated (the demo modules prove it).
- Minimal, intentional dependencies (`kotlinx-*` + Ktor) — good transitive-dependency hygiene for consumers.
- Dokka 2.0.0 is wired (docs only the `api/` package).

What's missing is purely the *release plumbing*: coordinates, a publishing plugin, POM metadata, signing, a license, a consumer README, and a release workflow.

## 1. Decisions — SETTLED

| # | Decision | Outcome |
|---|----------|---------|
| **D1** | Maven Central namespace | **`io.github.adventures92`** — verified by owning the GitHub account; no DNS record needed |
| **D2** | artifactId | **`sockit`** (was `socketio`: generic, and crowded on Central) → `io.github.adventures92:sockit` |
| **D3** | Publishing plugin | **vanniktech `com.vanniktech.maven.publish`** — implemented |
| **D4** | License | **Apache-2.0**, © Adven — `LICENSE` at repo root |
| **D5** | Kotlin package | `dev.adven.sockit` retained. Group and package are independent; only the group names the Central namespace. **Open:** revisit if `adven.dev` is registered, which would allow group `dev.adven` and package `dev.adven.sockit` |

Test fixtures copied from `kmp-socketio` (`socket-server.js`, `cert.pem`, `key.pem`) were removed and
the echo server rewritten from scratch; no upstream bytes remain and no MIT notice is owed.

<details>
<summary>Original decision table (superseded)</summary>

### Decisions needed from you (these gate everything)

| # | Decision | Why it matters | Recommendation |
|---|----------|----------------|----------------|
| **D1** | **Maven Central namespace** | Central verifies namespace ownership. `com.adven.*` requires proving you own `adven.com`. The zero-friction option is `io.github.<user-or-org>`, verified via a GitHub repo. | If you don't own a domain: **`io.github.zebpay-anand`** (or the org you publish under). If you own `adven.com`: `com.dev.adven.sockit`. |
| **D2** | **artifactId + final group** | Becomes the coordinate `group:socketio:version`. | `…:socketio:0.1.0` |
| **D3** | **Publishing plugin** | Raw `maven-publish` for KMP + Central Portal is a lot of boilerplate. | **vanniktech `com.vanniktech.maven.publish`** — de-facto standard for KMP→Central; auto-configures all target publications, sources jars, Dokka javadoc jar, signing, and the Central Portal upload. |
| **D4** | **License** | Central requires a license in the POM; open-source needs a LICENSE file. | **Apache-2.0** (common for KMP libs) — confirm or pick MIT. |
| **D5** | **Group-id package rename?** | Code is under `dev.adven.sockit`. If group becomes `io.github.…`, the *package* can stay `dev.adven.sockit` (group ≠ package), but some prefer alignment. | Keep package `dev.adven.sockit`; only the Maven **group** changes. No code move. |

</details>

**Remaining manual step (credentials):** create a GPG signing key and a Central Portal user token, and add them as repository secrets — `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_IN_MEMORY_KEY`, `SIGNING_IN_MEMORY_KEY_PASSWORD` (and `SIGNING_IN_MEMORY_KEY_ID` only when signing with a subkey). The build already reads them.

## 2. Sequenced work (after D1–D4 are answered)

### Phase A — Coordinates & publishing config
- Add `group` + `version` (start `0.1.0`) — in `gradle.properties` (`GROUP=…`, `VERSION_NAME=0.1.0`) so vanniktech picks them up.
- Add `com.vanniktech.maven.publish` to the version catalog + apply it in `socketio/build.gradle.kts` **only** (not the app modules).
- Configure the POM: name, description, URL, license (D4), SCM (`github.com/…/Sockit`), developer. 
- Javadoc jar from Dokka 2 (`JavadocJar.Dokka("dokkaGeneratePublicationHtml")`).
- Ensure the **Android** target (new AGP KMP library plugin) publishes a release variant; verify iOS klibs + JVM jar publications are generated.
- **Gate:** `./gradlew :socketio:publishToMavenLocal` then inspect `~/.m2/…` for all expected artifacts (android release, iosArm64, iosSimulatorArm64, jvm, `-kotlin-tooling-metadata`, sources, javadoc, `.module`).

### Phase B — License & consumer README
- Add top-level `LICENSE` (D4).
- Repurpose the earlier `docs/GUIDE.md` into a public `README.md`: one-paragraph pitch, the "no third-party Socket.IO/logging deps" selling point, install snippet (`implementation("<group>:socketio:0.1.0")`), a 10-line quickstart (connect → namespace → emit/emitWithAck → events), supported platforms/transports, and a link to the API docs. Add license + (later) Maven Central badges.

### Phase C — Release CI
- New workflow `.github/workflows/publish.yml`, triggered on `v*` tags:
  1. run the existing quality gates (`apiCheck spotlessCheck detekt jvmTest`) — never publish a red build;
  2. `./gradlew publishAndReleaseToMavenCentral --no-configuration-cache` using Central + signing secrets.
- Keep the existing `socketio-ci.yml` (PR gates) unchanged.
- Optionally add a `publishDokka`/GitHub Pages step for hosted API docs.

### Phase D — First release dry-run
- Tag `v0.1.0` on a test basis → confirm the artifact appears in the Central Portal staging, validate the POM, then release.
- After first publish, add the Maven Central version badge to the README.

## 3. Repo-structure note
Keep `:shared` / `:androidApp` / `iosApp` in the repo — they're the demo + the consumer-encapsulation gate (valuable to keep running in CI). Only `:socketio` gets the publishing plugin, so nothing else is published. No module split required.

## 4. Downstream unblock
Once the repo is **public**, the deferred `socketio-upstream-watch` cloud routine (item 5 of the conformance roadmap) can be created verbatim — the earlier `HTTP 403` was solely private-repo access.

## 5. What I need to start
Answer **D1–D4** (D5 is a recommendation you can just accept). Then I'll execute Phase A→C as reviewable commits on a `socketio-publishing` branch (stacked or off `develop`, your call), leaving credentials/secrets to you.
