# Connection State Recovery — Server Contract Spec

> **Gap:** HLD #13 · **Plan:** CH3 G-P3-2 · **Created:** 2026-06-19  
> **Authority:** [Socket.IO 4.6+ docs](https://socket.io/docs/v4/connection-state-recovery) + reference `socket.io` / `socket.io-client` in `socketio/src/jvmTest/resources/node_modules/`

**Project root:** `<repository root>`

---

## 1. Summary

| Field | Value |
|-------|-------|
| **Feature** | Socket.IO **connection state recovery** (server opt-in, 4.6+) |
| **Purpose** | After a *temporary* disconnect, restore namespace `sid`, server `rooms`/`data`, and **replay missed server→client events** |
| **Not the same as** | Engine.IO session resume, client `sendBuffer`/`recvBuffer`, or `pauseReconnect()` |
| **CMP v0.2 decision** | **Spec only — implementation deferred** (see §8) |

---

## 2. Server prerequisite

Recovery is **off by default**. The server must enable it explicitly:

```js
import { Server } from "socket.io";

const io = new Server({
  connectionStateRecovery: {
    maxDisconnectionDuration: 2 * 60 * 1000, // default 2 min
    skipMiddlewares: true,                        // default true
  },
});
```

| Option | Default | Meaning |
|--------|---------|---------|
| `maxDisconnectionDuration` | `120_000` ms | How long adapter keeps session + packet log |
| `skipMiddlewares` | `true` | On successful recovery, skip namespace middleware chain |

**Adapter support** (official matrix):

| Adapter | Recovery |
|---------|----------|
| In-memory (`SessionAwareAdapter`) | ✅ |
| Redis Streams | ✅ |
| MongoDB (≥ 0.3.0) | ✅ |
| Redis PUB/SUB | ❌ (packets not persisted) |
| Postgres / Cluster | WIP at doc time |

Our jvmTest echo server (`socket-server.js`) does **not** enable this option today.

---

## 3. Identifiers

| ID | Scope | Visibility | Role |
|----|-------|------------|------|
| **Engine.IO `sid`** | Transport session | Internal to Engine.IO | Polling/WS session; unrelated to Socket.IO recovery |
| **Socket.IO `sid`** | Namespace socket | **Public** — exposed as `NamespaceSocket.id` | Room membership key; restored on recovery |
| **`pid`** (private session id) | Namespace socket | **Private** — client stores, never logs in prod | Recovery handshake token; distinct from public `sid` |

Flow:

```text
First connect:
  Server CONNECT ack → {"sid":"<public>","pid":"<private>"}
  Client stores _pid = pid, id = sid

Reconnect (recovery attempt):
  Client CONNECT → {"pid":"<private>","offset":"<last>","…auth"}
  Server restoreSession(pid, offset) → session + missedPackets | null
```

---

## 4. Wire protocol

### 4.1 CONNECT (first connection)

```text
Client → 40{"token":"…"}           // auth merged with recovery fields when _pid set
Server → 40{"sid":"GNpW…","pid":"YHcX…"}
```

Reference: `socket.io/client-dist/socket.io.js` `_sendConnectPacket`, `onconnect(id, pid)`.

### 4.2 CONNECT (reconnect with recovery)

When client holds `_pid` and `_lastOffset`:

```text
Client → 40{"pid":"YHcX…","offset":"MzUPkW0","token":"…"}
```

Server `namespace._createSocket` reads `auth.pid` + `auth.offset`, calls `adapter.restoreSession`.

### 4.3 Server events carry offset suffix

For recoverable outbound events (no ack id, not volatile), server appends a **yeast** id as the **last JSON array element**:

```text
42["foo","bar","MzUPkW0"]
     ^event ^arg  ^offset (backward-compatible extra arg)
```

Client tracks `_lastOffset` when `_pid` is set and last arg is a `string` (after listeners run — official client does **not** strip offset from callback args).

**Requirement:** Server must emit **at least one** such event before disconnect, or client has no offset to send.

### 4.4 What is NOT stored server-side

Per `SessionAwareAdapter.broadcast`:

- Packets **with acknowledgement** (`packet.id` set) — ack fn not serializable
- **Volatile** emits (`socket.volatile.emit`)
- Non-EVENT packet types

---

## 5. Server state on disconnect

### 5.1 Recoverable disconnect reasons

Server persists session only when `connectionStateRecovery` is enabled **and** reason ∈:

```text
transport error | transport close | forced close | ping timeout
server shutting down | forced server close
```

Reference: `socket.io/dist/socket.js` `RECOVERABLE_DISCONNECT_REASONS`.

**Not recoverable:** `io client disconnect`, `io server disconnect` (intentional `socket.disconnect()`).

### 5.2 Persisted session shape

```ts
{
  sid: string,      // public socket id
  pid: string,      // private session id
  rooms: string[],
  data: unknown,    // socket.data
  disconnectedAt: number
}
```

### 5.3 On successful restore

Server reconstructs socket with `recovered = true`:

1. Rejoin `rooms`
2. Restore `data`
3. Replay `missedPackets` as EVENT packets to client
4. Optionally skip middlewares (`skipMiddlewares`)

Client sets `recovered = (pid && _pid === pid)` on CONNECT ack.

---

## 6. Failure modes (client sees fresh session)

| Condition | Server behavior | Client `recovered` |
|-----------|-----------------|-------------------|
| Session expired (`> maxDisconnectionDuration`) | New socket, new `sid`/`pid` | `false` |
| Unknown `pid` | New socket | `false` |
| `offset` not found in packet log | `restoreSession` → null | `false` |
| Intentional disconnect | No `persistSession` | `false` |
| Server without `connectionStateRecovery` | No `pid` in CONNECT ack | N/A (feature inactive) |

Apps must still handle full state sync on `recovered == false`.

---

## 7. Current `:socketio` library state

| Area | Today | Recovery gap |
|------|-------|----------------|
| CONNECT send | `buildAuthData()` only — no `pid`/`offset` | §4.2 missing |
| CONNECT parse | `parseConnectSid` reads `sid` only | `pid` ignored |
| Event dispatch | `parseEventData` — all array elements are args | offset not tracked / not stripped |
| Public API | `NamespaceSocket.id`, `connectionState` | no `recovered` |
| Client buffers | `sendBuffer` / `recvBuffer` while disconnected | **local only** — not server replay |
| Engine reconnect | Reuses Engine.IO `sid` on transport | separate layer |
| Tests | Echo server without recovery | no integration coverage |

Existing buffers cover **client emits before CONNECT** and **server events before CONNECT** — not **missed events during a drop after connected**.

---

## 8. CMP decision (CH3.2.2)

**Verdict: Won't implement in v0.2** — spec + HLD §22 only.

| Factor | Rationale |
|--------|-----------|
| Server opt-in | No known consumer server enables `connectionStateRecovery` |
| Product signal | Gap #13 deferred since v0.1; no ticket requesting parity |
| Mitigation exists | Engine reconnect + namespace reconnect + client buffers + app-level resync |
| Cost | Protocol changes, offset handling policy, `recovered` API (BCV), recovery-enabled test server |
| Risk if delayed | Apps on recovery-enabled servers lose missed server events until implemented |

### Trigger to implement (v0.3+)

Implement when **any** of:

1. Named backend enables `connectionStateRecovery` and mobile must consume `recovered` / missed events
2. G-P3-4 compatibility matrix includes a recovery-enabled server version as required
3. Product explicitly requests JS `socket.recovered` parity

### Implementation sketch (future — not in scope now)

| Step | Files / surface |
|------|-----------------|
| Store `_pid`, `_lastOffset` per namespace | `NamespaceSocketImpl` |
| Merge `pid`/`offset` into CONNECT payload | `sendConnectPacketOnWorker` |
| Parse `pid` from CONNECT ack; expose `recovered` | `api/NamespaceSocket.kt`, BCV |
| Strip trailing offset from dispatched events (cleaner than JS) | `parseEventData` / `dispatchEvent` |
| Preserve `_pid` across reconnect; clear on intentional close | `onClose` / `onServerDisconnect` |
| Integration test | Node server with `connectionStateRecovery: {}`; force `engine.close()`; assert event replay |

---

## 9. Doc chain updates

| File | Change |
|------|--------|
| `architecture.md` | §15 row #13 → v0.2 spec §22; §16 note; new §22 summary |
| `gap-code-hardening-plan.md` | G-P3-2 done; CH3.2.1 + CH3.2.2 checked |
| `execution-state.md` | Last verification block |

---

## 10. References

- https://socket.io/docs/v4/connection-state-recovery
- https://socket.io/docs/v4/server-options/#connectionstaterecovery
- Server: `socket.io/dist/namespace.js` `_createSocket`, `socket.js` `_onclose`
- Client: `socket.io/client-dist/socket.io.js` `_sendConnectPacket`, `emitEvent`, `onconnect`
- Adapter: `socket.io-adapter/dist/in-memory-adapter.js` `SessionAwareAdapter`
