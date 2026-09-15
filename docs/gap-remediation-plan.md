# Sockit `:socketio` — Gap Remediation Plan

> **For agentic workers:** One **phase per session** (G1 → G2a → G3 → G4). Read/update this file at start and end. Cross-check HLD [`architecture.md`](./architecture.md) before each step.

**Created:** 2026-06-18  
**Source:** Cumulative verification (Phases 0–8) — gaps A–D  
**Scope:** `<repository root>/socketio/` only (production + its tests)  
**Out of scope:** `shared/`, demo app, repo-root CI changes, doc-only edits in `docs/` (optional §G5)

---

## Doc chain

| Layer | File | Role |
|-------|------|------|
| HLD | `architecture.md` | What & why — §5, §5.1, §10, §20 |
| LLD | `implementation-plan.md` | Original phase specs (reference only) |
| Gap audit | `cumulative-verification-p0-p66.md` | Prior P0–P6.6 audit |
| **This plan** | **`gap-remediation-plan.md`** | Surgical fixes for verification gaps A–D |
| Execution | `execution-state.md` | Update when each G-phase gate passes |

---

## Session protocol

1. Read **Current phase** below — run only that phase's steps.
2. Complete steps in order; run **Step verify** after each step.
3. Any `commonMain` or platform `actual` change → run **KMP_COMPILE** before marking done.
4. Run **Phase gate** when all phase checkboxes are `[x]`.
5. Update **Current phase**, **Last updated**, checkboxes, **Last verification**.
6. Stop — one phase per session.

### KMP compile gate (mandatory)

```bash
cd <repository root>
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
```

### Sub-agent prompt

```text
Sockit socketio — Gap phase {G} only.

Read:
- docs/gap-remediation-plan.md (Current phase + step list)
- docs/architecture.md (HLD refs in step table)

Rules: surgical diff only · KMP_COMPILE on commonMain changes · phase gate must pass · update gap-remediation-plan.md · no next phase.
```

---

## Overall status

| Field | Value |
|-------|-------|
| **Current phase** | `complete` (G1 → G2a → G2b → G3 → G4) |
| **Last updated** | 2026-06-18 |
| **Blockers** | None |
| **G2b (polling binary)** | **Implemented** — `EngineIoCodec.encodePollingBatch` + inbound `b` records |

---

## Gap register

| ID | HLD | Issue | Status |
|----|-----|-------|--------|
| **A** | §5.1 | `ParseError` in public API but never emitted; decode failures close connection | **done** |
| **B** | §10 | Outbound `ByteString` emit not wired; WS sends text-only | **done** (WS + polling) |
| **C** | §5 | `SocketClient` missing `isConnected()` / `isDisconnected()` on aggregate state | **done** |
| **D** | §20 / P8.5 | Dokka plugin applied; no `api/` package filter | **done** |

---

## Execution order

```text
G1 ParseError (A)     — internal error-path fix
    ↓
G2a Outbound binary   — WS path + polling explicit fail (B)
    ↓
G3 Client snapshots   — public API + BCV dump (C)
    ↓
G4 Dokka api/ filter  — build config only (D)

G2b Polling binary   — OPTIONAL post-v0.1 (spike in §G2b)
G5 Doc sync          — OPTIONAL, out of :socketio scope
```

---

## G1 — Gap A: Wire `ParseError` (HLD §5.1)

**HLD intent:** Parse warnings on `errors: Flow` — **non-terminal**. Today decode failures → transport error → close + reconnect.

**Design (locked):**

```text
Inbound decode failure (EngineIoCodec / SocketIoCodec)
  → SocketError.ParseError(raw) on errors Flow
  → Logger.error(tag, raw, cause)
  → drop packet; do NOT close transport; do NOT reconnect

Fatal errors (HTTP fail, WS drop, write fail) → unchanged path
```

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| G1.1 | Parse failure marker | §5.1 | Create `protocol/ProtocolParseException.kt` | compiles |
| G1.2 | Transport classify parse vs fatal | §5.1 | Modify `transport/Transport.kt`, `PollingTransport.kt`, `WebSocketTransport.kt` | compiles |
| G1.3 | Engine: ParseError without close | §5.1 | Modify `engineio/EngineConnection.kt` | compiles |
| G1.4 | Unit test mapping | §5.1 | Create `commonTest/.../EngineParseErrorMappingTest.kt` | test PASS |
| G1.5 | Integration test | §5.1 | Create `jvmTest/.../ParseErrorIntegrationTest.kt` | test PASS |

### G1.1 — `ProtocolParseException`

**Create:** `socketio/src/commonMain/kotlin/dev/adven/sockit/protocol/ProtocolParseException.kt`

```kotlin
internal class ProtocolParseException(
    val raw: String,
    cause: Throwable? = null,
) : Exception(raw, cause)
```

- [ ] G1.1 Create `ProtocolParseException.kt`
- [ ] G1.1 **KMP_COMPILE**

### G1.2 — Transport parse vs fatal

**Modify:** `transport/Transport.kt`

- Add `protected fun onParseError(raw: String, cause: Throwable? = null)` → emit on `EVENT_ERROR` wrapping `ProtocolParseException(raw, cause)`.

**Modify:** `transport/PollingTransport.kt`

- In `processIncomingPackets` catch block: call `onParseError(data, e)` instead of `onError(e)`.

**Modify:** `transport/WebSocketTransport.kt`

- In `onWsText` decode catch: call `onParseError(data, e)` instead of `scope.launch { onError(e) }`.

**Do not change:** POST/GET failures, send failures, close frames — remain fatal.

- [ ] G1.2 Transport parse error path
- [ ] G1.2 **KMP_COMPILE**

### G1.3 — Engine no-close on parse

**Modify:** `engineio/EngineConnection.kt`

In transport `EVENT_ERROR` handling / `mapTransportError`:

```kotlin
is ProtocolParseException -> {
    emitError(SocketError.ParseError(cause.raw))
    return  // no onClose, no reconnect
}
```

Ensure `ConnectionManager` → `NamespaceSocketImpl.onEngineError` still forwards (already wired).

- [ ] G1.3 Engine ParseError non-terminal
- [ ] G1.3 **KMP_COMPILE**

### G1.4 — Unit test

**Create:** `socketio/src/commonTest/kotlin/dev/adven/sockit/engineio/EngineParseErrorMappingTest.kt`

- Assert `ProtocolParseException("bad")` maps to `SocketError.ParseError("bad")`.
- Assert generic `IllegalArgumentException` still maps to existing fatal type (not `ParseError`).

```bash
./gradlew :socketio:jvmTest --tests "dev.adven.sockit.engineio.EngineParseErrorMappingTest"
```

- [ ] G1.4 Unit test written and PASS

### G1.5 — Integration test

**Create:** `socketio/src/jvmTest/kotlin/dev/adven/sockit/ParseErrorIntegrationTest.kt`

- Connect via embedded or Node echo server.
- Inject malformed inbound packet (extend `EmbeddedEngineIoServer` hook or fault-inject after connect).
- Assert `socket.errors` emits `SocketError.ParseError`.
- Assert `connectionState` remains `Connected` (non-terminal).

```bash
./gradlew :socketio:jvmTest --tests "dev.adven.sockit.ParseErrorIntegrationTest"
```

- [ ] G1.5 Integration test written and PASS

### G1 phase gate

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
./gradlew :socketio:jvmTest --tests "*ParseError*"
./gradlew :socketio:detekt :socketio:apiCheck
```

- [ ] G1 phase gate PASS
- [ ] Update **Current phase** → `G2a`

---

## G2a — Gap B: Outbound `ByteString` emit — WebSocket path (HLD §10)

**HLD intent:** `emit` accepts `ByteString`; binary events = `BINARY_EVENT` placeholders + attachment frames.

**Today:** Inbound binary works. Outbound throws in `NamespaceSocketImpl.payloadToJson`. `WebSocketTransport.doSend` is text-only.

**Design (locked):**

```kotlin
// Create: protocol/OutboundEngineMessage.kt (internal)
internal data class OutboundEngineMessage(
    val packet: EnginePacket,
    val binaryAttachments: List<ByteString> = emptyList(),
)
```

WS send sequence (matches `EmbeddedEngineIoServer`):

```text
Frame.Text(EngineIoCodec.encode(BINARY_EVENT))
  → Frame.Binary(...) for each attachment
```

**Polling (G2a scope):** If `binaryAttachments.isNotEmpty()` → explicit `SendFailed` (not silent). Full polling support deferred to G2b.

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| G2a.1 | Outbound envelope + engine buffer | §10 | `OutboundEngineMessage.kt`, `EngineConnection.kt`, `ConnectionManager.kt` | compiles |
| G2a.2 | Namespace encode path | §10 | `NamespaceSocketImpl.kt` | compiles |
| G2a.3 | Transport send text+binary | §10 | `Transport.kt`, `WebSocketTransport.kt`, `PollingTransport.kt` | compiles |
| G2a.4 | Unit + integration tests | §10 | `NamespaceBinaryEncodeTest.kt`, `BinaryEmitIntegrationTest.kt` | tests PASS |
| G2a.5 | Regression guard | §11 gap #1 | Rerun drain/upgrade tests | PASS |

### G2a.1 — Internal outbound model

**Create:** `socketio/src/commonMain/kotlin/dev/adven/sockit/protocol/OutboundEngineMessage.kt`

**Modify:** `engineio/EngineConnection.kt`

- Change `writeBuffer` from `ArrayDeque<EnginePacket>` to `ArrayDeque<OutboundEngineMessage>`.
- Update `send` / `sendPackets` / `flush` / `onDrain` to use envelope.
- **Drain accounting:** 1 logical outbound message = 1 drain unit (even if WS emits text + N binary frames).

**Modify:** `socketio/ConnectionManager.kt`

- `fun send(messages: List<OutboundEngineMessage>)` delegating to engine.

- [ ] G2a.1 OutboundEngineMessage + engine buffer
- [ ] G2a.1 **KMP_COMPILE**

### G2a.2 — Namespace encode path

**Modify:** `socketio/NamespaceSocketImpl.kt`

Replace `encodeEventPackets` / `sendBuffer`:

```text
partition payloads → jsonArgs + binaryAttachments (via encodePayload)
if binaryAttachments.isEmpty()
  → existing SocketPacket.Event → OutboundEngineMessage(packet only)
else
  → BinaryAssembler.encodeBinaryEvent(namespace, event, binaryAttachments, *jsonArgs)
  → OutboundEngineMessage(EnginePacket.Message(BinaryEvent), frames)
```

- Remove `payloadToJson` throw on `SocketPayload.Binary`.
- Change `sendBuffer` to `ArrayList<OutboundEngineMessage>`.

- [ ] G2a.2 Namespace binary encode
- [ ] G2a.2 **KMP_COMPILE**

### G2a.3 — Transport WS send

**Modify:** `transport/Transport.kt`, `WebSocketTransport.kt`, `PollingTransport.kt`

- `doSend` accepts `List<OutboundEngineMessage>`.
- **WebSocketTransport:** for each message → text frame → binary frames per attachment.
- **PollingTransport:** if attachments non-empty → `onError(...)` → existing fatal/SendFailed path.

- [ ] G2a.3 Transport outbound binary (WS)
- [ ] G2a.3 **KMP_COMPILE**

### G2a.4 — Tests

**Create:** `socketio/src/commonTest/kotlin/dev/adven/sockit/socketio/NamespaceBinaryEncodeTest.kt`

- Pure unit test of encode logic with `ByteString` → correct attachment count + packet type.

**Create:** `socketio/src/jvmTest/kotlin/dev/adven/sockit/BinaryEmitIntegrationTest.kt`

- WS connect → `emit("echoBinary", byteString)` using embedded server handler (`EmbeddedEngineIoServer` lines 225–238).
- Assert `events("echoBinaryBack")` receives binary payload.

```bash
./gradlew :socketio:jvmTest --tests "*NamespaceBinaryEncode*" --tests "*BinaryEmit*"
```

- [ ] G2a.4 Unit test PASS
- [ ] G2a.4 Integration test PASS

### G2a.5 — Regression guard

```bash
./gradlew :socketio:jvmTest --tests "dev.adven.sockit.engineio.EngineDrainAccountingTest"
./gradlew :socketio:jvmTest --tests "dev.adven.sockit.engineio.UpgradeChaosTest"
```

- [ ] G2a.5 Drain + upgrade regression PASS

### G2a phase gate

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
./gradlew :socketio:jvmTest --tests "*Binary*" --tests "dev.adven.sockit.engineio.EngineDrainAccountingTest" --tests "dev.adven.sockit.engineio.UpgradeChaosTest"
./gradlew :socketio:detekt :socketio:apiCheck
```

- [ ] G2a phase gate PASS
- [ ] Update **Current phase** → `G3`

---

## G2b — Gap B (optional): Polling outbound binary

**Status:** Deferred for v0.1. Run only if product requires binary emit before WS upgrade.

### G2b.0 — Spike (read-only, ≤2h)

| Action | Output |
|--------|--------|
| Read Engine.IO v4 polling binary format (`b` prefix / base64 batching) | Decision checkbox below |
| If clear → implement in `PollingTransport.doSend` | Remove G2a polling guard |

- [x] G2b.0 Spike complete — decision: **`implement`** (Engine.IO v4 `b`+base64 polling batch)

---

## G3 — Gap C: `SocketClient` snapshot helpers (HLD §5)

**HLD intent:** `isConnected()` / `isDisconnected()` mirrored on `SocketClient` aggregate state.

**Semantics:** Same as `NamespaceSocket` — `Connecting` / `Reconnecting` / `Failed` → both return `false`.

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| G3.1 | Add helpers | §5 | `api/SocketClient.kt` | compiles |
| G3.2 | BCV dump | §20 | `api/jvm/socketio.api` | apiCheck PASS |
| G3.3 | Unit test (optional) | §5 | `commonTest/.../SocketClientSnapshotTest.kt` | test PASS |

### G3.1 — Implementation

**Modify:** `socketio/src/commonMain/kotlin/dev/adven/sockit/api/SocketClient.kt`

```kotlin
public fun isConnected(): Boolean =
    connectionState.value is ConnectionState.Connected

public fun isDisconnected(): Boolean =
    connectionState.value is ConnectionState.Disconnected
```

- [ ] G3.1 SocketClient snapshot helpers
- [ ] G3.1 **KMP_COMPILE**

### G3.2 — Binary Compatibility Validator

```bash
./gradlew :socketio:apiDump
./gradlew :socketio:apiCheck
```

Commit updated `socketio/api/jvm/socketio.api`.

- [ ] G3.2 apiDump committed
- [ ] G3.2 apiCheck PASS

### G3.3 — Unit test (optional)

**Create:** `socketio/src/commonTest/kotlin/dev/adven/sockit/api/SocketClientSnapshotTest.kt`

- Mirror logic from existing `snapshotHelpersMatchConnectionState` integration pattern if practical.

- [ ] G3.3 Unit test PASS (or skipped with BCV-only proof)

### G3 phase gate

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
./gradlew :socketio:apiCheck
./gradlew :socketio:detekt
```

- [ ] G3 phase gate PASS
- [ ] Update **Current phase** → `G4`

---

## G4 — Gap D: Dokka scoped to `api/` (HLD §20, Phase 8.5)

**HLD intent:** Dokka HTML documents `dev.adven.sockit.api` only.

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| G4.1 | Dokka V2 source filter | §20 | `socketio/build.gradle.kts`, optional `gradle.properties` | dokkaHtml |
| G4.2 | Manual HTML review | §20 | `socketio/build/dokka/html/` | api-only index |

### G4.1 — Dokka config

**Modify:** `socketio/build.gradle.kts`

- Configure `dokka { dokkaSourceSets.configureEach { ... } }` with:
  - `documentedVisibilities` = public only
  - `sourceRoots` limited to `src/commonMain/kotlin/dev/adven/sockit/api` (and platform `api/` dirs if any)

**Optional — Modify:** `gradle.properties`

```properties
org.jetbrains.dokka.experimental.gradle.pluginMode=V2EnabledWithHelpers
```

Reference: Dokka 2.0.0 in `gradle/libs.versions.toml`.

- [ ] G4.1 Dokka api/ filter configured

### G4.2 — Verify

```bash
./gradlew :socketio:dokkaHtml
```

Manual check: HTML index lists only `dev.adven.sockit.api.*` — no `protocol/`, `internal/`, `transport/`, etc.

- [ ] G4.2 dokkaHtml PASS
- [ ] G4.2 Manual review: api-only

### G4 phase gate

```bash
./gradlew :socketio:dokkaHtml :socketio:detekt :socketio:apiCheck
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
```

- [ ] G4 phase gate PASS
- [ ] Update **Current phase** → `complete`

---

## G5 — Optional doc sync (out of `:socketio` scope)

Not required for gap closure. Apply in a separate docs-only session if desired.

| Doc | Fix |
|-----|-----|
| `architecture-diagrams.md` §7 | `emitAwait` completes on **queue**; async transport `SendFailed` → `errors` only |
| `implementation-plan.md` | Rename `androidInstrumentedTest` → `androidDeviceTest` (AGP 9) |
| `architecture-diagrams.md` §5 | Use `emit(Subscribe(...))` in sequence example |
| `execution-state.md` | Add row linking to this plan when G-phases complete |

- [x] G5 Doc sync (optional)

---

## Cross-cutting rules

| Rule | Check |
|------|-------|
| Library module only | No edits under `shared/`, `androidApp/`, `iosApp/` |
| No new runtime deps | `rg "hildan\|kmp-xlog\|socket\.io-client" socketio/src` → empty |
| Android API safety | `rg "removeFirst\(\)\|removeLast\(\)" socketio/src/commonMain` → empty |
| Minimal diff | No unrelated refactors or formatting sweeps |
| BCV | Run `apiDump` only after G3 (public API change) |

---

## Risk register

| Risk | Mitigation |
|------|------------|
| G1: silent packet drop | `Logger.error` + `ParseError.raw` preserves payload |
| G2: writeBuffer / drain regression | G2a.5 rerun `EngineDrainAccountingTest`, `UpgradeChaosTest` |
| G2: polling binary complexity | G2a explicit fail; G2b spike before implement |
| G3: aggregate vs namespace state | KDoc on `SocketClient` helpers: reflect **aggregate** state |
| G4: Dokka V2 API drift | Pin to catalog version 2.0.0 |

---

## Last verification

```text
G1–G4 + G2b session — 2026-06-18 (cursor)
  Steps completed: G1.1–G1.5, G2a.1–G2a.5, G2b (polling encode/decode), G3.1–G3.2, G4.1–G4.2
  KMP_COMPILE → PASS
  jvmTest (*ParseError*, *Binary*, EngineDrainAccounting, UpgradeChaos) → PASS
  apiCheck + detekt + dokkaGeneratePublicationHtml (api/ only) → PASS
  Notes: G2b uses Engine.IO v4 polling `b`+base64 batching; inbound polling binary decode added to PollingTransport
```

Update this block at the end of each session:

```text
G{N} session — YYYY-MM-DD (agent)
  Steps completed: G{N}.x …
  KMP_COMPILE → PASS/FAIL
  Phase gate → PASS/FAIL
  Notes: …
```

---

## Estimated effort

| Phase | Effort |
|-------|--------|
| G1 ParseError | 0.5 day |
| G2a WS binary emit | 1 day |
| G2b Polling binary (optional) | 0.5–1.5 days |
| G3 Snapshot helpers | 0.25 day |
| G4 Dokka | 0.25 day |
| **Total (G1–G4, G2b deferred)** | **~2 days** |
