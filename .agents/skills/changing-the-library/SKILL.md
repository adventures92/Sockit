---
name: changing-the-library
description: What to update alongside a change to :socketio — public API dumps, KDoc, the guide, the changelog, conformance vectors. Use when adding, renaming, removing or changing behaviour of anything in the library, before opening a pull request.
---

# Changing the library

A code change is rarely only a code change. This is what travels with it, and why.

The compiler catches none of this. `apiCheck` and `scripts/check-docs.sh` catch some of it —
run them rather than trusting a reading of this page.

## Start here

```bash
./gradlew spotlessApply
./gradlew :socketio:apiCheck spotlessCheck :socketio:detekt
./gradlew :socketio:jvmTest
./scripts/check-docs.sh
```

`apiCheck` fails if you changed the public surface without re-dumping it. `check-docs.sh` fails if
the guide names a type that no longer exists, quotes a stale version, or has a broken link.

## What each kind of change drags with it

### You changed anything in `api/`

- `./gradlew :socketio:apiDump` — commit both `socketio/api/jvm/socketio.api` and
  `socketio/api/socketio.klib.api`. CI fails otherwise.
- **KDoc on every public declaration.** Only `api/` is documented by Dokka; an undocumented public
  type ships an empty reference page.
- Search `guide/` for the old name. `check-docs.sh` catches a *removed* type, but it cannot catch
  prose that still describes the old behaviour under a name that still exists.
- A `CHANGELOG.md` entry under `## [Unreleased]`.
- **Adding a public type is a compatibility commitment.** Removing it later is a breaking change.
  Prefer `internal` until a consumer actually needs it.

### You changed a default in `SocketOptions`

- `guide/options.md` tabulates every default. It will not fail a build when it is wrong — it will
  just lie. Update it.
- If the default changes observable behaviour, that is a `### Changed` changelog entry, not a
  `### Fixed` one.

### You changed error or connection semantics

- `guide/errors.md` and `guide/connection-state.md` describe when each `SocketError` occurs and
  what is terminal.
- `guide/reconnection.md` if retry behaviour moved.
- Anything about `emit` / `emitAwait` delivery guarantees is stated in three places —
  `guide/emitting.md`, `guide/errors.md`, and the `emitAwait` KDoc. They must agree.

### You touched the protocol, engine or transports

- A vector in `ProtocolConformanceTest`, copied verbatim from the upstream
  [`socket.io-protocol`](https://github.com/socketio/socket.io-protocol) test suite.
- Confirm connection state is still only mutated on the `WorkQueue` worker. Mutating it elsewhere
  reintroduces the upgrade/drain/close races the design exists to prevent, and no test will tell
  you — it will surface as a flake months later.
- No JDK 21+ collection APIs in `commonMain` (`removeFirst()`, `removeLast()`): they crash on
  Android below API 35.

### You added or removed a target

- `guide/install.md` (the module table), `guide/limitations.md`, and the relevant page under
  `guide/` Platforms.
- `AGENTS.md` "What gets published".
- **Removing a published target breaks every consumer of it.** Treat the target list as a
  commitment, not a default.

### You added a dependency

- Is any of its types visible from `api/`? Then it must be `api()`, not `implementation()`.
  Kotlin/Native exposes everything regardless, so a mistake here only breaks Android and JVM
  consumers — which is exactly what the `consumer-smoke` CI job exists to catch.
- Check it is not on the forbidden list in `AGENTS.md`. CI enforces that.

### You changed CI or release plumbing

- A workflow triggered by something a workflow does will **not** fire: GitHub suppresses triggers
  for events raised by `GITHUB_TOKEN`. This has caught `docs.yml` twice. Use `workflow_call` from
  the producing workflow instead of hoping for a trigger.
- A matrix job never reports its bare name — it reports `job (values)`. Requiring the bare name as
  a status check blocks every pull request forever. That is what `server-compatibility-gate` is
  for.
- **An action pinned to a dead Node runtime warns on every run and eventually breaks.** GitHub
  forces the action onto a newer Node and annotates the run. Check the action's *released tag*, not
  its default branch: `peaceiris/actions-mdbook` fixed this on `main` but has cut no release since
  April 2024, so there was no tag to move to. When an action is a thin wrapper around installing one
  static binary, install the binary instead — that is what `docs.yml` does for mdBook, and it pins
  the version besides. `node-version:` on `setup-node` is a separate axis: that is the Node *our*
  test server runs on, and it needs to stay on a supported LTS.

## The guide is prose, and prose rots

`guide/` is hand-written; the compiler does not read it. `scripts/check-docs.sh` catches
structural drift — a removed type, a stale version, a broken link — but it cannot catch a
paragraph that describes behaviour you just changed.

When you change behaviour, grep the guide for the concept, not just the identifier. The pages most
likely to go quietly wrong are `options.md` (defaults), `errors.md` (when each error fires),
`reconnection.md` (what the library does versus what you must do), and `limitations.md` (which
becomes wrong precisely when you fix something).

## What not to put in the changelog

It is a product document, not a work log. CI fixes, refactors and build changes do not belong
there — git history holds them. Ask whether a consumer could observe the change. If not, leave it
out.
