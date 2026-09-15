# WebTransport Feasibility Spike (CH3 G-P3-3)

> **Created:** 2026-06-19 · **Verdict:** **NO-GO** for v0.2 mobile implementation via Ktor  
> **Related:** `Transports.WEBTRANSPORT` constant + stub factory (feature-flagged)

**Project root:** `<repository root>`

---

## 1. Question

Can Engine.IO WebTransport be implemented for Android (OkHttp) and iOS (Darwin) using the existing Ktor `HttpClient` stack?

---

## 2. What Engine.IO WebTransport requires

Reference: `engine.io-client` / `socket.io` JS client (`transports/webtransport.js`).

| Requirement | Detail |
|-------------|--------|
| Protocol | HTTP/3 over QUIC |
| API | Browser [`WebTransport`](https://developer.mozilla.org/en-US/docs/Web/API/WebTransport) — not plain HTTP or WebSocket |
| Session | `new WebTransport(httpsUri, transportOptions)` |
| Data plane | One **bidirectional stream** per session; packet encoder/decoder pipes |
| Wire | Same Engine.IO packet codec as WS/polling (open packet, then framed messages) |
| Upgrade | Server may offer `webtransport` in OPEN `upgrades`; JS client **prefers** WT over WS when both listed |
| Server default | **Disabled** — must enable `transports: [..., "webtransport"]` on Engine.IO server |

```text
doOpen:
  WebTransport(https://host/path?EIO=4&transport=webtransport&…)
  → ready → createBidirectionalStream()
  → readable.pipeThrough(packetDecoder) / writable.pipeThrough(packetEncoder)
  → write { type: "open" } → onOpen()
```

---

## 3. Ktor client survey (3.2.2)

| Module / engine | WebTransport client? | Used by `:socketio` |
|-----------------|----------------------|---------------------|
| `ktor-client-okhttp` | ❌ HTTP/1.1–2 only | Android |
| `ktor-client-darwin` | ❌ `NSURLSession` HTTP/WS | iOS |
| `ktor-client-cio` | ❌ HTTP/1.x only | JVM tests |
| `ktor-client-websockets` | WebSocket only | All platforms |
| `ktor-client-webrtc` | WebRTC P2P, not WebTransport | — |
| `ktor-client-webtransport` | **Does not exist** | — |

**Conclusion:** Ktor provides no WebTransport client abstraction. OkHttp and Darwin wrappers cannot be extended through existing Ktor plugins.

---

## 4. Platform native survey

| Platform | Native WebTransport API? | Practical path |
|----------|--------------------------|----------------|
| **Browser** | ✅ `WebTransport` | N/A (out of mobile scope) |
| **Node.js** | Polyfill `@fails-components/webtransport` | JVM test-only; not Android/iOS |
| **Android** | ❌ Not in OkHttp / system SDK | Web API only in WebView/Chrome; [Stack Overflow consensus](https://stackoverflow.com/questions/79212011): not an OS API — Cronet unconfirmed |
| **iOS** | ❌ Not in `NSURLSession` | No public QUIC/WebTransport client API |

Mobile apps today reach servers via **HTTP(S) + WebSocket** (what we ship). WebTransport is a **browser QUIC API**, not a thin wrapper over the same stacks we use.

---

## 5. Options considered

| Option | Verdict |
|--------|---------|
| A — Ktor `HttpClient` + new plugin | **Blocked** — no upstream module; engines lack QUIC bidi streams |
| B — OkHttp QUIC / Cronet custom bridge | **High cost** — new platform dep, HTTP/3 cert handling, Engine.IO stream codec; Cronet WT support unverified |
| C — Darwin Network.framework QUIC | **High cost** — custom Kotlin/Native bridge; no Engine.IO reference impl |
| D — Stub + constant + feature flag (chosen) | **Ship now** — API surface ready; `experimentalWebTransport` gates normalization; open fails fast |
| E — Defer constant entirely | Rejected — plan requires registration point for future work |

---

## 6. Decision

| Field | Value |
|-------|-------|
| **Go/No-Go** | **NO-GO** for functional WebTransport on Android/iOS in v0.2 |
| **Shipped in CH3.3.1** | `Transports.WEBTRANSPORT`, `experimentalWebTransport` flag, `WebTransportTransport` stub |
| **Default behavior** | Unchanged — `webtransport` dropped at `SocketOptions.build()` unless flag enabled |
| **Revisit when** | (1) Ktor or OkHttp ships stable WebTransport client, (2) product server requires WT, (3) Cronet/Network.framework path validated with Engine.IO echo |

### Implementation path (future)

```text
expect class PlatformWebTransport(...) {
  suspend fun open(uri: String): WebTransportSession
}
// commonMain: WebTransportTransport uses PlatformWebTransport, not HttpClient
// androidMain: Cronet or dedicated QUIC lib (spike required)
// iosMain: Network.framework or third-party QUIC (spike required)
```

Do **not** route through `HttpClient.webSocket` — wrong protocol.

---

## 7. Tests / verification (CH3.3.1)

| Test | Assert |
|------|--------|
| `SocketOptionsTest` | `webtransport` dropped by default; kept when `experimentalWebTransport = true` |
| `TransportFactoryTest` | Factory returns `WebTransportTransport` for name `webtransport` |

Stub open → `EVENT_ERROR` with not-implemented message (integration test optional; covered by unit factory test).

---

## 8. Doc updates

| File | Change |
|------|--------|
| `architecture.md` | §23 WebTransport summary |
| `gap-code-hardening-plan.md` | G-P3-3 done |
| `socketio/README.md` | experimental flag + limitations note |

---

## 9. References

- https://socket.io/docs/v4/server-options/ (Engine.IO transports)
- https://socket.io/docs/v4/changelog/4.6.0 (webtransport in engine.io-client)
- `node_modules/engine.io/build/transports/webtransport.js`
- `node_modules/socket.io/client-dist/socket.io.js` (WT transport class)
