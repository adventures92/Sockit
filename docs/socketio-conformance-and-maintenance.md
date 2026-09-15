# Socket.IO Client — Conformance & Maintenance Report

> **Status:** analysis / proposal (no code changed yet)
> **Scope:** `:socketio` — the in-house Socket.IO v5 / Engine.IO v4 client
> **Date:** 2026-07-27
> **Companion docs:** [`architecture.md`](architecture.md) (HLD), [`CLAUDE.md`](../CLAUDE.md) (build/constraints)

This report answers two questions:

1. **How do we keep this client compatible with future Socket.IO changes?**
2. **Are we spec-compliant, and can the client handle everything the server offers?**

All findings are grounded in the current source under
`socketio/src/commonMain/kotlin/dev/adven/sockit/` and verified against the
upstream protocol specs (see [§4](#4-sources-of-truth)).

---

## Follow-up review (2026-07-29)

A review of the merged PR #1 / PR #2 work found five additional gaps, now fixed:

- Server events (including ack requests) dispatched before the app called `events(name)` were
  silently dropped — see `respondsToServerInitiatedAckEvenWhenSubscribedAfterItArrives`.
- Mixing binary and scalar `emit`/ack-reply arguments silently reordered them — see
  `mixedBinaryAndScalarArgsPreserveOrderOnEmit`.
- `emitWithAck`'s timeout timer wasn't cancelled once the ack resolved early (coroutine hygiene,
  not user-visible) — see `ackTimeoutDoesNotMisfireAfterFastAck`.
- A dropped ack reply (namespace disconnected before `Ack.send()` ran) was silent — now reports
  `SocketError.SendFailed` — see `ackSendAfterDisconnectReportsSendFailedOnErrorsFlow`.
- A single long-polling message exceeding the server's `maxPayload` was sent anyway and, absent
  the resulting transport error, would have retried unchanged forever — see
  `oversizedPollingMessageIsDroppedNotRetriedForever`.

---

## 1. The core insight: this is a wire protocol, not a library API

The client implements two **frozen, versioned wire protocols**:

| Protocol | Revision | Stable since | Advertised by |
|----------|----------|--------------|---------------|
| Socket.IO | **v5** (protocol version 5) | 2020 | Socket.IO packet framing |
| Engine.IO | **v4** (`EIO=4`) | 2020 | `EIO` query param + handshake |

Both revisions are sent by the server on **every** handshake. The current
`EIO=4` value is hardcoded in
`transport/TransportFactory.kt:73`.

**Consequence for maintenance:** "new changes in Socket.IO" almost never means
the *protocol* changed. It means a new **server software release** (4.7 → 4.8 →
…). As long as the server keeps answering `EIO=4` + Socket.IO protocol v5 —
which the entire 4.x line and current server line do — a **conformant** client
stays compatible with no code changes.

So the real risk is **not** future protocol drift. It is the gap between what
the client does today and what the v5 spec **already requires**. That is
[§2](#2-conformance-gap-analysis).

---

## 2. Conformance gap analysis

All 7 Engine.IO packet types and all 7 Socket.IO packet types are decoded; the
upgrade probe, auth, reconnect, and binary reassembly all exist. The gaps below
are where the client diverges from the spec or from what a server can legitimately
send.

### 🔴 P0 — Spec violation that breaks server features

#### 2.1 Server-initiated acknowledgements — ✅ RESOLVED (2026-07-27)

- **Was:** `onEvent` received an `ackId` and, when non-null, only logged
  `"event includes server ack id … — not handled"`. No `ACK` packet was ever
  sent back, so any server handler written as
  `socket.emit("x", data, (response) => { … })` hung or timed out waiting for a
  client ACK that never arrived — a **MUST**-violation of the v5 spec (*"the
  receiver MUST respond with an `ACK` packet with the same event ID"*).
- **Fix:** `SocketEvent` now carries an optional `ack: Ack?` responder
  (`api/Ack.kt`), non-`null` only when the server requested an acknowledgement.
  Consumers call `event.ack?.send(...)`; the reply is enqueued on the
  `WorkQueue` and encoded as an `ACK`/`BINARY_ACK` on the same namespace and ack
  id (`NamespaceSocketImpl.AckResponder` + `encodeAckMessages`;
  `BinaryAssembler.encodeBinaryAck` added for the binary case). Sent at most
  once; dropped if the namespace has closed.
- **Tests:** `ConnectionIntegrationTest.respondsToServerInitiatedAck` (round-trips
  through the Node echo server's `callAck` handler) and
  `BinaryAssemblerTest.encodeBinaryAckRoundTrip`.
- **Known follow-up:** a *binary* server EVENT that carries an ack id is still
  unsupported — `SocketPacket.BinaryEvent` has no `id` field and type-5 decode
  discards it. Rare; tracked as a P2 follow-up.

### 🟠 P1 — Correctness gaps (data loss / hangs)

#### 2.2 `maxPayload` parsing & enforcement — ✅ RESOLVED (2026-07-27)

- **Was:** `OpenHandshake` dropped `maxPayload` (via `ignoreUnknownKeys`) and
  `EngineConnection.flush` sent the whole pending slice in one write with no byte
  cap. On the polling transport a batch over `maxPayload` (server default 1 MB)
  is rejected (**HTTP 413**), surfacing as a transport error / dropped packets.
- **Fix:** `maxPayload` is now parsed (`OpenHandshake` → `EnginePacket.Open` →
  `EngineConnection.maxPayload`). `EngineIoCodec.pollingBatchFit` computes how
  many leading messages fit within the byte budget (UTF-8, always ≥ 1), and
  `EngineConnection.flush` caps each polling POST to that prefix; the remainder
  stays buffered and is flushed by the existing `onDrain` loop. WebSocket framing
  is unaffected. `0`/absent `maxPayload` means "no limit".
- **Tests:** `EngineIoCodecTest.pollingBatchFitSplitsByMaxPayload`,
  `pollingBatchFitAlwaysSendsAtLeastOne`, `decodeOpenParsesMaxPayload`,
  `decodeOpenDefaultsMaxPayloadToZeroWhenAbsent`.

#### 2.3 `emitWithAck` timeout & disconnect handling — ✅ RESOLVED (2026-07-27)

- **Was:** `emitWithAck` suspended on a `CompletableDeferred<String>` that only
  completed on ACK receipt; `clearAckCallbacks` dropped pending callbacks without
  failing the deferred, so a disconnect mid-ack hung the caller **indefinitely**.
- **Fix:** Pending acks are now tracked with a result **and** failure path
  (`PendingAck`). On disconnect, `clearAckCallbacks` fails each pending ack with
  `SocketError.TransportClosed`. A new `socketOptions { ackTimeoutMs = … }`
  (default `0` = disabled) fails a still-pending ack with `SocketError.Timeout`
  after the interval. Either way the flow terminates instead of hanging.
- **Tests:** `ConnectionIntegrationTest.emitWithAckTimesOutWhenServerNeverAcks`.
- **Known rough edge:** ack/connect failures still surface as the *internal*
  `SendFailedException`/`ConnectionFailedException` across the public boundary, so
  consumers must catch broadly rather than pattern-match. Tracked as an API-polish
  follow-up (P3).

### 🟡 P2 — Feature limitations — ✅ RESOLVED (2026-07-27)

#### 2.4 Binary attachments as real bytes — ✅ RESOLVED

- **Was:** reassembly hex-substituted attachments into the JSON, so consumers got
  hex **text**, not bytes; there was no outbound `BINARY_ACK` producer.
- **Fix:** `BinaryAssembler` now hands the completion the placeholder data plus the
  ordered raw attachments; `NamespaceSocketImpl` maps a **top-level** binary arg to
  `SocketPayload.Binary` (real bytes) for both events and acks. Outbound binary acks
  are produced by `BinaryAssembler.encodeBinaryAck` (added in P0). Nested attachments
  (rare) keep the hex-in-JSON representation.
- **Tests:** `binaryEventRoundTripThroughNamespaceSocket` and
  `BinaryEmitIntegrationTest` now assert `SocketPayload.Binary` with exact bytes.

#### 2.5 Typed / nested auth — ✅ RESOLVED

- **Was:** `SocketOptions.auth` was `Map<String, String>` (string values only).
- **Fix:** `auth` is now a `JsonObject` built via a `JsonObjectBuilder` DSL, so values
  may be strings, numbers, booleans, or nested objects. `auth { put("k","v") }` stays
  source-compatible. Verified end-to-end via the echo server's `getHandshake` ack.

#### 2.6 Structured `CONNECT_ERROR` — ✅ RESOLVED

- **Was:** the whole JSON string went into `SocketError.ConnectError(data)`.
- **Fix:** parsed into `ConnectError(message, data?)` — see 2.6 handling in
  `NamespaceSocketImpl`. Verified against the `/no` namespace rejection.

### 🟢 P3 — Minor / optional

- **Heartbeat direction.** EIO v4 has the **server** send pings and the client
  only pong. `EngineConnection.startHeartbeat` *also* proactively pings on an
  interval (EIO v3-style) layered on top of correct v4 pong handling.
  Interoperates, but adds traffic and isn't strictly v4.
- **Volatile emits** — not implemented. Rarely used; skip unless required.
- **`Upgrade` / `Noop` at engine `onPacket`** fall into `else -> Unit`. The
  probe-path upgrade is handled separately, so this is benign today.

---

## 3. Maintenance strategy — staying compatible going forward

Four practices, mostly built on infrastructure already in the repo.

### 3.1 Track the specs, not the JS library
Watch **releases + `Readme.md`** on the two spec repos in [§4](#4-sources-of-truth).
A protocol-revision bump (v5→v6, `EIO=4`→`5`) is the **only** event that forces
protocol work in this client — and it is always a loud, announced change.

### 3.2 Official conformance test-suite — ✅ ADOPTED (2026-07-27)
`socketio/socket.io-protocol` ships a `test-suite/` (`test-suite.js`) describing the
exact packet exchanges a compliant implementation must satisfy. Its vectors are now
codified as `ProtocolConformanceTest` in **`commonTest`** (runs on JVM *and* iOS): each
test copies an upstream wire vector verbatim and asserts our codecs produce/consume it —
connect (main/custom namespace, with/without payload), `CONNECT_ERROR`, disconnect,
plain-text events, events with ack ids, binary attachments, binary acks, and
unknown-packet-type rejection. Because CI already runs `jvmTest` (see [CI](#ci) in
CLAUDE.md), **spec compliance is now a PR gate**. When a protocol revision changes, these
vectors fail first, pointing at exactly what drifted.

### 3.3 Keep extending the server-compatibility matrix
The repo already has `docker-compose.server-matrix.yml` (socket.io 4.5.4–4.8.1)
and a `server-compatibility` CI job. Maintenance action per new server release:
add the version to the matrix, run the suite. This is the early-warning system
for behavioral drift within a protocol revision.

### 3.4 Automate the upstream watch
A scheduled agent can check both protocol repos + socket.io server releases on a
cadence and alert (or open an issue) when a protocol revision changes or a new
server minor ships — so a bump is never a surprise.

---

## 4. Sources of truth

| Spec | Repo | Watch for |
|------|------|-----------|
| Socket.IO protocol v5 | `github.com/socketio/socket.io-protocol` (`Readme.md`, `test-suite/`) | protocol-revision bump; test-suite changes |
| Engine.IO protocol v4 | `github.com/socketio/engine.io-protocol` (`Readme.md`) | `EIO` revision bump; handshake field changes |
| Server releases | `github.com/socketio/socket.io/releases` | new minors → add to matrix |

---

## 5. Recommended sequence

1. ~~**P0 — server acks (2.1).**~~ ✅ **Done (2026-07-27)** — see 2.1.
2. ~~**P1 — `maxPayload` (2.2) + `emitWithAck` timeout (2.3).**~~ ✅ **Done (2026-07-27)** — see 2.2, 2.3.
3. ~~**Conformance harness (3.2).**~~ ✅ **Done (2026-07-27)** — `ProtocolConformanceTest` in commonTest; see 3.2.
4. ~~**P2 — binary bytes / typed auth / structured CONNECT_ERROR.**~~ ✅ **Done (2026-07-27)** — see 2.4–2.6. (Binary-event-with-ack-id remains a deferred niche follow-up.)
5. **Upstream watch (3.4).** Ongoing safety net.
6. **P3** — revisit only if a use case demands it.

Each `:socketio` change must pass the existing gates:

```bash
./gradlew :socketio:apiCheck spotlessCheck :socketio:detekt
```

and any public `api/` change needs a matching `./gradlew :socketio:apiDump`.
