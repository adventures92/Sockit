# Sockit — Mobile Socket.IO KMP Library

**Status:** Approved design (2026-06-16)  
**Target project:** `<repository root>`  
**Reference:** [kmp-socketio](https://github.com/HackWebRTC/kmp-socketio) (behavior model, not a dependency)

---

## 1. Goal

Ship a **mobile-only** (Android + iOS) Kotlin Multiplatform **Socket.IO client library** as a standalone artifact inside Sockit, with:

- **Coroutine-first public API** (`StateFlow`, `Flow`, `suspend` helpers)
- **No third-party protocol or logging libraries** — only official `kotlinx-*` + Ktor
- **In-house wire codec** for Engine.IO v4 + Socket.IO v5
- **Proactive fixes** for known [kmp-socketio closed issues](https://github.com/HackWebRTC/kmp-socketio/issues?q=is%3Aissue+state%3Aclosed)

The existing `shared` module remains the **demo app**; a new `socketio` module is the **library**.

---

## 2. Locked decisions

| Topic | Decision |
|-------|----------|
| Ship targets | `android`, `iosArm64`, `iosSimulatorArm64` |
| Test target | `jvm()` — hosts the Node echo-server integration suite, and is published as a supported target |
| Android `minSdk` | 24 |
| Public API | Coroutine-only (no callback `on`/`emit` API) |
| `SocketOptions` | Builder DSL `socketOptions { }` → immutable snapshot (not public data class) |
| API encapsulation | **Strict:** only `api/` types are public; all wire/engine code `internal`; event-name constants not exported |
| Protocol | In-house `protocol/` module |
| Allowed deps | `kotlinx-coroutines`, `kotlinx-serialization-json`, `kotlinx-io`, Ktor client |
| Forbidden deps | `socketio-kotlin`, `socket.io-client-java`, `kmp-xlog`, `kotlinx-datetime` |
| Maven coordinate | `io.github.adventures92:sockit` (Kotlin package stays `dev.adven.sockit`) |
| Shared `HttpClient` | Optional via `SocketOptions.httpClient` |
| Logging | Injected `Logger` interface; `Logger.NoOp` default |

---

## 3. Repository layout (target)

```text
Sockit/
├── socketio/                    # KMP library (new)
│   └── src/
│       ├── commonMain/...       # ~95% of code
│       ├── androidMain/...      # OkHttp HttpClient
│       ├── iosMain/...          # Darwin HttpClient
│       ├── commonTest/...       # protocol + pure unit tests
│       ├── jvmTest/...          # Node echo-server integration tests
│       ├── androidInstrumentedTest/...  # OkHttp runtime smoke
│       └── iosSimulatorArm64Test/...    # Darwin runtime smoke
├── shared/                      # Demo Compose app (existing)
├── androidApp/
├── iosApp/
└── docs/
    ├── architecture.md          # this file
    ├── implementation-plan.md
    └── execution-state.md       # resumable agent state (update each session)
```

---

## 4. Layered architecture

```text
┌─────────────────────────────────────────────────────────┐
│  api/          ONLY public surface (see §4.1)            │  ← apps import this
├─────────────────────────────────────────────────────────┤
│  socketio/     ConnectionManager, NamespaceSocketImpl,   │  internal
│                SocketClientRegistry                      │
├─────────────────────────────────────────────────────────┤
│  engineio/     EngineConnection, UpgradeController      │  internal
├─────────────────────────────────────────────────────────┤
│  transport/    PollingTransport, WebSocketTransport     │  internal
├─────────────────────────────────────────────────────────┤
│  protocol/     EngineIoCodec, SocketIoCodec, Packets    │  internal
├─────────────────────────────────────────────────────────┤
│  connection/   ReconnectPolicy                          │  internal
├─────────────────────────────────────────────────────────┤
│  internal/     WorkQueue, EventBus, SubscriptionHandle,  │  internal
│                StreamEventNames (subscribe/unsubscribe)  │
├─────────────────────────────────────────────────────────┤
│  platform/     createPlatformHttpClient (expect/actual)  │  internal
└─────────────────────────────────────────────────────────┘
```

### 4.1 API encapsulation (strict OOP)

Consumers see **only** the `api` package. Everything else is implementation detail.

| Visibility | Rule |
|------------|------|
| **Public** | Types and functions listed in §5 — no more |
| **`internal`** | All classes in `protocol/`, `transport/`, `engineio/`, `socketio/`, `connection/`, `internal/`, `platform/` |
| **Constants** | `subscribe` / `unsubscribe` strings live in `internal` (e.g. `StreamEventNames`); **not** in public API |
| **Implementations** | `NamespaceSocketImpl`, `ConnectionManager`, codecs, transports — never public |
| **Construction** | Apps use factories (`SocketClient.connect`, `socketOptions { }`); no public constructors on impl types |

**Gradle:** enable `explicitApi()` on `commonMain` so accidental public leakage fails compile.

**Consumer-side verification (requires a depending module):** `internal` visibility is enforced only across Gradle module boundaries. While `:socketio` is standalone, you cannot compile-check that apps fail to import `StreamEventNames` (or any `internal` type). **Phase 8.1** runs the consumer encapsulation gate once `shared` (or any consumer) adds `implementation(project(":socketio"))`.

**Custom events:** implement public `StreamCommand` interface (or use public `SocketCommand` data class). Do not expose new constants for app-specific event names from the library.

### Threading model

All mutable socket state is updated on a **single worker** coroutine:

```kotlin
Dispatchers.Default.limitedParallelism(1, "socketio-worker")
```

- **What:** One serial queue for state machine transitions.
- **Why:** Prevents upgrade/drain/close races (kmp-socketio [#31](https://github.com/HackWebRTC/kmp-socketio/pull/31)).
- **Public API:** May be called from any thread; enqueues work onto the worker.

### Android API safety

- Never use JDK 21+ `List.removeFirst()` / `removeLast()` in code that compiles to Android DEX.
- Use `ArrayDeque.removeFirstOrNull()`, index-based removal, or explicit `removeAt(0)`.

---

## 5. Public API (coroutine-first)

### Entry point

```kotlin
val options = socketOptions {
    auth["token"] = jwt
    transports(Transports.POLLING, Transports.WEBSOCKET)
    httpClient = appKtorClient
    logger = myLogger
}

val client = SocketClient.connect("https://stream.example.com", options)

val socket = client.namespace("/futures")
socket.open()

if (socket.isConnected()) { /* snapshot guard */ }
socket.connectionState.collect { state -> /* UI — unchanged */ }
socket.events("rateUpdate").collect { event -> /* handle */ }

socket.emit(Subscribe(buildJsonObject { put("pair", "BTC-INR") }))
socket.emit(Unsubscribe(buildJsonObject { put("pair", "BTC-INR") }))
socket.emit(SocketCommand("custom", buildJsonObject { put("x", 1) }))

socket.close()
client.close()
```

### Public surface (exhaustive)

| Type / function | Responsibility |
|-----------------|----------------|
| `SocketClient` | Multiplexes managers per origin; factory for namespaces |
| `NamespaceSocket` | Per-namespace connect, emit, event `Flow`, ack; `isConnected()` / `isDisconnected()` |
| `socketOptions { }`, `SocketOptions` | Config DSL → immutable snapshot (`internal` constructor) |
| `Transports` | Public wire-name constants (`POLLING`, `WEBSOCKET`); optional — literals still accepted |
| `StreamCommand` | Outbound command contract: `eventName` + `payload` |
| `Subscribe`, `Unsubscribe` | `StreamCommand` with **library-fixed** event names; body only |
| `SocketCommand` | `StreamCommand` for arbitrary event + `JsonElement` body |
| `ConnectionState`, `SocketError`, `SocketEvent`, `SocketPayload` | State, errors, inbound events |
| `Logger` | Injectable logging; `Logger.NoOp` default |

All other types are **`internal`** — not part of the published contract.

### Suspend helpers (optional sugar on `NamespaceSocket`)

```kotlin
suspend fun NamespaceSocket.openAwait()       // suspends until Connected or Failed(SocketError)
suspend fun NamespaceSocket.emitAwait(...)    // suspends until queued; pre-queue reject only
```

### Connection state snapshots

`connectionState: StateFlow<ConnectionState>` is unchanged — use it for UI collection and full lifecycle (`Connecting`, `Reconnecting`, `Failed`, etc.).

For imperative guards (before `emit`, in callbacks), expose **snapshot booleans only** — no extra `Flow` / `StateFlow`:

```kotlin
fun isConnected(): Boolean =
    connectionState.value is ConnectionState.Connected

fun isDisconnected(): Boolean =
    connectionState.value is ConnectionState.Disconnected
```

| Function | `true` when |
|----------|-------------|
| `isConnected()` | `connectionState.value is Connected` |
| `isDisconnected()` | `connectionState.value is Disconnected` |

`Connecting`, `Reconnecting`, and `Failed` → both return `false`. Use `connectionState` for those cases.

Implemented on `NamespaceSocket` (and mirrored on `SocketClient` if it exposes aggregate state). Reads are thread-safe via `StateFlow.value`; mutations still go through `WorkQueue`.

### Outbound commands (`StreamCommand`)

Typed outbound API. Event-name **constants** (`"subscribe"`, `"unsubscribe"`) are **`internal`** — apps cannot import them.

```kotlin
interface StreamCommand {
    val eventName: String
    val payload: JsonElement
}

class Subscribe(val payload: JsonElement) : StreamCommand {
    override val eventName: String get() = /* internal StreamEventNames.SUBSCRIBE */
}

class Unsubscribe(val payload: JsonElement) : StreamCommand {
    override val eventName: String get() = /* internal StreamEventNames.UNSUBSCRIBE */
}

data class SocketCommand(
    override val eventName: String,
    override val payload: JsonElement,
) : StreamCommand
```

| Type | `eventName` | Body |
|------|-------------|------|
| `Subscribe` | Fixed by library (`internal` constant) | Any `JsonElement` |
| `Unsubscribe` | Fixed by library (`internal` constant) | Any `JsonElement` |
| `SocketCommand` | Caller supplies | Any `JsonElement` |
| Custom | Implement `StreamCommand` in app module | Any `JsonElement` |

`Subscribe` / `Unsubscribe` **cannot** override event name. Different event → `SocketCommand` or app-owned `StreamCommand` impl.

```kotlin
// NamespaceSocket — preferred outbound path
fun emit(command: StreamCommand)
suspend fun emitAwait(command: StreamCommand)

// String emit retained for escape hatch / interop
fun emit(event: String, vararg payloads: Any?)
```

```kotlin
// internal/StreamEventNames.kt — not visible outside module
internal object StreamEventNames {
    const val SUBSCRIBE = "subscribe"
    const val UNSUBSCRIBE = "unsubscribe"
}
```

### SocketOptions — builder DSL

Network client libraries rarely expose a public `data class` with many defaulted fields. Common patterns:

| Ecosystem | Options pattern |
|-----------|-----------------|
| Ktor `HttpClient` | Receiver DSL `HttpClient(CIO) { install(...) }` |
| OkHttp | `OkHttpClient.Builder()` → `build()` |
| Retrofit | `Retrofit.Builder()` |
| Socket.IO (JS) | Plain object literal `{ transports: [...] }` |
| gRPC Kotlin | DSL / builder |

**Chosen:** Ktor-style `socketOptions { }` DSL → immutable `SocketOptions` at `build()`. Idiomatic for Kotlin KMP; scales as fields grow; avoids `copy()` drift. Java `Builder` class deferred until needed.

```kotlin
fun socketOptions(block: SocketOptionsBuilder.() -> Unit): SocketOptions

class SocketOptionsBuilder {
    var path: String = "/socket.io/"
    fun transports(vararg names: String)
    fun auth(block: MutableMap<String, String>.() -> Unit)
    fun extraHeaders(block: MutableMap<String, List<String>>.() -> Unit)
    var upgrade: Boolean = true
    var timeoutMs: Long = 20_000
    var reconnection: Boolean = true
    var reconnectionAttempts: Int = Int.MAX_VALUE
    var reconnectionDelayMs: Long = 1_000
    var reconnectionDelayMaxMs: Long = 5_000
    var randomizationFactor: Double = 0.5
    var multiplex: Boolean = true
    var forceNew: Boolean = false
    var trustAllCerts: Boolean = false
    var httpClient: HttpClient? = null
    var logger: Logger = Logger.NoOp
    internal fun build(): SocketOptions
}

/** Immutable snapshot — `internal` constructor; apps use `socketOptions { }` only. */
class SocketOptions internal constructor(/* read-only fields */)
```

Defaults unchanged from prior spec (`multiplex = true`, `reconnection = true`, etc.). Options are read once at `connect()`; library does not observe later mutations.

#### Transport names (`Transports` + JS-compatible filtering)

Engine.IO wire names are plain strings on the HTTP/WebSocket URL (`?transport=polling`). The public API mirrors **Socket.IO JS**: consumers may pass **library constants** or **literal strings**; inner logic normalizes the list the same way as `engine.io-client`.

```kotlin
public object Transports {
    public const val POLLING: String = "polling"
    public const val WEBSOCKET: String = "websocket"
}
```

| Input at `socketOptions { }` | After `build()` normalization | Connect behavior |
|------------------------------|-------------------------------|------------------|
| `transports(Transports.POLLING, Transports.WEBSOCKET)` | `["polling", "websocket"]` | Polling first, websocket allowed for upgrade |
| `transports("polling", "websocket")` | same | same (literals OK) |
| `transports("foo", Transports.POLLING)` | `["polling"]` | Invalid names **silently dropped** (JS parity) |
| `transports("foo")` | *build fails* | `IllegalArgumentException`: no transports available |
| *(omit `transports`)* | `["polling", "websocket"]` | Default |

**Semantics (unchanged):** ordered preference for initial connect (`first()`), full list is the **allowed set** for server-offered upgrades (`filterUpgrades`). Not a try-until-success fallback list.

**v0.1 supported set:** `polling`, `websocket` (default). `webtransport` is **experimental** — `Transports.WEBTRANSPORT` + `experimentalWebTransport = true` registers a stub factory; see §23 and [`webtransport-feasibility-spike.md`](./webtransport-feasibility-spike.md). Future full implementations add platform bridges, not just constants.

**Normalization at `build()`:** filter to supported wire names (preserve order). If the result is empty → fail fast with a clear message (equivalent to JS `"No transports available"`). Optional `Logger.debug` when entries are dropped — default `Logger.NoOp` keeps JS silent behavior.

```kotlin
class SocketOptionsBuilder {
    fun transports(vararg names: String)  // accepts Transports.* or literals
    internal fun build(): SocketOptions   // normalizes transports before snapshot
}
```

### 5.1 Error model (gap #2, #6)

Terminal failures surface via `ConnectionState.Failed`. Non-terminal / operational failures use a dedicated hot stream so apps can log or retry without guessing from strings.

```kotlin
sealed interface SocketError {
    data class Timeout(val phase: String) : SocketError
    data class TlsFailure(val cause: Throwable?) : SocketError
    data class ParseError(val raw: String) : SocketError
    data object PingTimeout : SocketError
    data class TransportClosed(val reason: String?) : SocketError
    data class SendFailed(val cause: Throwable?) : SocketError
    data class ConnectError(val message: String) : SocketError
}
```

| Surface | When |
|---------|------|
| `connectionState: StateFlow<ConnectionState>` | Connect / reconnect lifecycle; `Failed` is terminal until `open()` again |
| `errors: Flow<SocketError>` | Send failures while connected, parse warnings, transport glitches before reconnect |
| `Logger.error` | Always — internal detail + throwable |

**Send failure path (must not swallow):**

```text
Pre-queue reject (reserved event, encode error)
  → emitAwait / emitWithAck: complete exceptionally with SendFailed
  → errors Flow also emits SendFailed

Transport.send() throws / WS write fails (async, after enqueue)
  → map to SocketError.SendFailed
  → emit on errors Flow
  → EngineConnection: close transport + schedule reconnect (if reconnection enabled)
  → prior emitAwait that already returned: unaffected (queue semantics — Option A)
  → emitWithAck still in flight: may complete exceptionally with SendFailed
```

No silent drops. Fire-and-forget `emit()` still reports via `errors` and triggers reconnect policy.
`emitAwait` suspends until the event is **accepted into the outbound queue** (WorkQueue → engine `writeBuffer`); it does not await transport drain.

### 5.2 Subscription cleanup

`events(name): Flow<SocketEvent>` is cold; collection ties to caller scope. Internal `SubscriptionHandle` exists for any imperative `off` needs inside the library — **not** exposed publicly.

---

## 6. Multiplexing & multiple instances

```text
SocketClient (per origin key, when multiplex=true)
 └── ConnectionManager
      └── EngineConnection          ← one physical connection
           ├── NamespaceSocket "/"
           └── NamespaceSocket "/private"
```

| Configuration | Physical engines | Behavior |
|---------------|------------------|----------|
| Same URL, `multiplex=true` (default) | 1 | Namespaces share engine; reconnect affects all |
| Same URL, `forceNew=true` | N | Independent engines per client |
| Different URLs | N | Independent |

Namespaces on the same client are **interdependent** (shared engine). Separate `SocketClient` instances with `forceNew` or different URLs are **independent**.

### Registry lifecycle (gap #9 — no orphaned managers)

Internal `SocketClientRegistry` (not public) replaces kmp-socketio's static `IO` map:

| Rule | Behavior |
|------|----------|
| Cache key | `scheme://host:port` when `multiplex=true` and `forceNew=false` |
| Cache hit | Return existing `ConnectionManager` for same origin |
| `forceNew=true` | New manager; not registered in global cache |
| `client.close()` | Decrement ref count; evict manager when last namespace + client released |
| Same namespace twice | Same `NamespaceSocket` instance (or documented idempotent `open()`) |

Callers own `SocketClient` lifetime. Do not leak clients — always `close()` when done.

---

## 7. Authentication

The library does **not** implement OAuth or token refresh. It forwards credentials you supply:

| Channel | `SocketOptions` field | Used for |
|---------|----------------------|----------|
| Socket.IO auth object | `auth: Map<String, String>` | Namespace `CONNECT` packet JSON |
| HTTP headers | `extraHeaders: Map<String, List<String>>` | Polling GET/POST, WS upgrade |

Token refresh is an **app concern**: observe `ConnectionState.Failed` / `errors`, refresh token, call `close()` + `connect()` with new options.

If `SocketOptions.httpClient` is the app's Ktor client (with `Auth` plugin), HTTP-layer auth applies automatically to polling; Socket.IO `auth` map is still separate unless the app copies the token into both.

---

## 8. Shared Ktor `HttpClient`

**Optional.** When provided:

- Library reuses it for polling and WebSocket (caller must install Ktor `WebSockets` plugin if using websocket transport).
- TLS, timeouts, and HTTP interceptors match the rest of the app.

When omitted:

- Library creates a platform client via `createPlatformHttpClient()` (OkHttp on Android, Darwin on iOS).

**Lifecycle:** If the app passes a shared client, the **app owns** it — do not close the client while sockets are active.

---

## 9. Logging

Three tiers — consumer channel is the only public surface:

| Tier | API | When active | Content |
|------|-----|-------------|---------|
| **Release (default)** | `Logger.NoOp` | Always unless app wires a logger | Zero output |
| **Consumer debug** | `Logger` via `SocketOptions.logger` | App debug builds | Lifecycle + error **category** only — no PII, URLs, wire payloads, or throwables |
| **Library dev** | `internal SocketInternalLog` | `-Psocketio.internalLogging=true` when building `:socketio` | Full wire/FSM detail — **not** importable by consumers |

```kotlin
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)

    companion object NoOp : Logger { /* all no-op */ }
    // Logger.essential { tag, level, message, throwable -> ... } — sanitized sink helper
}
```

- Default: `Logger.NoOp` — zero overhead, no transitive logging deps.
- Internal routing via `SocketLog`: wire/protocol/FSM → `SocketInternalLog`; lifecycle → sanitized `Logger` under single tag `Logger.TAG` (`SocketIO`).
- Library dev flag: `./gradlew -Psocketio.internalLogging=true :socketio:…`
- Avoids kmp-xlog / `kotlinx-datetime` crashes ([kmp-socketio #18](https://github.com/HackWebRTC/kmp-socketio/issues/18), [#19](https://github.com/HackWebRTC/kmp-socketio/issues/19)).

Suggested consumer log tags: `EngineConnection`, `ConnectionManager`, `NamespaceSocket`, `Transport`.

---

## 10. Protocol module (in-house)

### Engine.IO v4 (text)

| Type char | Meaning |
|-----------|---------|
| `0` | Open (handshake JSON: sid, pingInterval, pingTimeout, upgrades) |
| `1` | Close |
| `2` | Ping |
| `3` | Pong |
| `4` | Message (wraps Socket.IO packet) |
| `5` | Upgrade |
| `6` | noop |

Batching: multiple packets may arrive in one HTTP body; decoder splits by packet boundaries.

### Socket.IO v5 (inside Engine.IO message)

| Type char | Meaning |
|-----------|---------|
| `0` | CONNECT |
| `1` | DISCONNECT |
| `2` | EVENT |
| `3` | ACK |
| `4` | CONNECT_ERROR |
| `5` | BINARY_EVENT |
| `6` | BINARY_ACK |

Binary events use attachment placeholders in JSON + separate binary frames; `BinaryAssembler` reassembles.

### Payload types for `emit`

`String`, `Boolean`, `Number`, `JsonElement`, `ByteString` (kotlinx-io). Other types are rejected at API boundary.

---

## 11. Transport & engine behavior

### Connection sequence

1. **Polling handshake** — `GET /engine.io/?EIO=4&transport=polling` → `Open` packet with `sid`.
2. **Namespace connect** — Socket.IO `CONNECT` on chosen namespace.
3. **Heartbeat** — schedule ping from `pingInterval`; reset on pong; close on `pingTimeout`.
4. **Upgrade (optional)** — open WS probe → ping/pong → pause polling → send `upgrade` → switch transport.
5. **Steady state** — events over WS (or polling if upgrade disabled).

### Upgrade FSM (fixes drain race)

`UpgradeController` tracks explicit phases: `IDLE → PROBING → PAUSING_POLL → UPGRADING → DONE`.

- Probe ping **must not** decrement `writeBuffer` or emit engine-level drain for buffer accounting.
- Transport switch only when **both** polling pause complete **and** upgrade drain acknowledged.
- `onDrain(transport, count)`: if `count > writeBuffer.size`, log + no-op (never throw).
- Covered by jvmTest: close → immediate reopen (kmp-socketio ut-case-analysis scenario).

### WebSocket open (gap #3)

After Ktor `webSocket { }` enters the session block (handshake complete), **always** call `onOpen()` — never gate on `DefaultClientWebSocketSession` or engine type.

- Response headers: best-effort read when available; **not** a prerequisite for open.
- **websocket-only** (`transports = listOf("websocket")`) must work on OkHttp (Android) and Darwin (iOS) — verified in Phase 6.5.

### Reconnect

`ReconnectPolicy`: exponential backoff with jitter, `reconnectionAttempts` cap, `reset()` on successful open.

---

## 12. Platform HTTP clients

| Platform | Engine | Notes |
|----------|--------|-------|
| Android | Ktor OkHttp | Required for `trustAllCerts` dev mode ([#26](https://github.com/HackWebRTC/kmp-socketio/issues/26)) |
| iOS | Ktor Darwin | Standard TLS |
| JVM (test) | Ktor CIO | Local integration tests only |

`trustAllCerts=true` is **dev-only**; document security warning in README.

---

## 13. kmp-socketio issue register

| Issue | Prevention in this design |
|-------|---------------------------|
| #29 `removeFirst()` API &lt; 35 | `removeFirstOrNull()` / no JDK 21 collection APIs in commonMain |
| #27 background disconnect | v0.2 `pauseReconnect()` / `resumeReconnect()` primitives (§21); app decides when to call |
| #26 `trustAllCerts` Android TLS | OkHttp `actual` from day 1 |
| #24 private connection state | Public `StateFlow<ConnectionState>` |
| #22 shared HttpClient | `SocketOptions.httpClient` |
| #18/#19 datetime / xlog | No kmp-xlog; injectable `Logger` |
| #31 upgrade drain race | `UpgradeController` FSM + dedicated jvmTest |
| WASM/JS/desktop | Out of scope |

---

## 14. Testing strategy (gap #5)

| Layer | Where | Method |
|-------|-------|--------|
| Protocol codec | `commonTest` | Golden string encode/decode |
| WorkQueue, ReconnectPolicy, UpgradeController | `commonTest` | Unit tests |
| Transports, engine, socket | `jvmTest` | Node `socket-server.js` echo server (CIO) |
| Upgrade race + chaos | `jvmTest` | close → reopen; close during probe |
| **Android runtime** | `androidInstrumentedTest` | OkHttp: connect + echo + websocket-only |
| **iOS runtime** | `iosSimulatorArm64Test` | Darwin: connect + echo + websocket-only |
| Compile gate | CI | `compileAndroidMain`, `compileKotlinIosSimulatorArm64`, `compileKotlinJvm` |

JVM/CIO tests prove protocol + state machine. **Phase 6.5 is mandatory before v0.1** — OkHttp and Darwin differ from CIO (WS session types, TLS).

Echo server: reuse `socket-server.js`; instrumented tests point at `10.0.2.2:PORT` (emulator) or host IP (device) via test manifest/build config.

---

## 15. Gap traceability (kmp-socketio-mobile-gaps)

Source: shared gap analysis used to avoid repeating kmp-socketio architectural mistakes.

| Gap | Topic | Addressed in |
|-----|-------|--------------|
| #1 | Upgrade/drain/close races | §4 threading, §11 Upgrade FSM, Phase 5 |
| #2 | Send failures ignored | §5.1 error model, Phase 4.3 / 5.2 / 6.2 |
| #3 | WS `onOpen` session gate | §11 WebSocket open, Phase 4.3, 6.5 |
| #4 | Callback API | §5 coroutine API, Phase 1.2 / 6 |
| #5 | JVM-only tests | §14 testing, Phase 6.5 |
| #6 | Untyped errors | §5.1 `SocketError`, Phase 1.2 |
| #7 | Hard deps | §2 in-house protocol, injectable `Logger` |
| #8 | Threading | §4 `WorkQueue`, Phase 1.1 |
| #9 | Multiplex / global IO | §6 registry, Phase 6.1 / 6.3 |
| #10 | Protocol limits | §10, README compatibility matrix (Phase 8) |
| #11 | TLS / HttpClient | §8, Phase 3 |
| #12 | Lifecycle observer | **v0.2 design** — §21 (CH3 G-P3-1) |
| #13 | Connection state recovery | **v0.2 spec** — §22 (CH3 G-P3-2); impl deferred |

---

## 16. Out of scope (v0.1)

- App-specific integration (any consumer app)
- Desktop, JS, Wasm, Linux, Windows targets
- Callback-based public API
- Server-side Socket.IO
- Foreground service for Android
- Token refresh / credential storage
- Connection state recovery **implementation** (server contract spec'd §22; code deferred until backend enables feature)

> **Promoted to v0.2 (gap #12):** Reconnect pause/resume **primitives** — design §21. **When** to pause is always app-owned; library never observes lifecycle or network.
>
> **Promoted to v0.2 spec (gap #13):** Connection state recovery — [`connection-state-recovery-spec.md`](./connection-state-recovery-spec.md). **Won't implement in v0.2**; trigger in §22 when server opts in.

---

## 17. Agent execution model

Implementation runs **phase-by-phase** via sub-agents. Three-doc chain:

| Layer | File |
|-------|------|
| HLD | `architecture.md` |
| LLD | `implementation-plan.md` |
| Execution | `execution-state.md` — granular steps, HLD refs, phase gates |

- One phase per agent session.
- Each step in `execution-state.md` maps HLD § → LLD task → file → verify command.
- Phase gate must pass before advancing `Current phase`.

---

## 18. Dependencies (version catalog additions)

To add in `gradle/libs.versions.toml` during implementation:

```toml
[versions]
coroutines = "1.10.2"
ktor = "3.2.2"
serialization = "1.8.1"
kotlinxIo = "0.8.2"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-io-core = { module = "org.jetbrains.kotlinx:kotlinx-io-core", version.ref = "kotlinxIo" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
```

> **Note:** Confirm exact versions against Sockit's Kotlin 2.4.0 / AGP 9.0.1 before first compile if resolver conflicts appear.

---

## 19. Success criteria (v0.1)

- [x] `socketio` module compiles Android + iOS + JVM(test)
- [x] jvmTest: connect, emit/on echo, ack, reconnect, websocket-only, multiplex two namespaces
- [x] jvmTest: upgrade close→reopen race passes consistently
- [x] androidInstrumentedTest: OkHttp connect + echo + websocket-only
- [x] iosSimulatorArm64Test: Darwin connect + echo + websocket-only
- [x] `SendFailed` propagated (unit or integration test simulating transport write failure)
- [x] Public `connectionState` + `errors` flows work
- [x] `StreamCommand` / `Subscribe` / `Unsubscribe` / `emit(command)` work
- [x] `Transports` constants + JS-style transport list normalization (Phase 6.6)
- [x] No public types outside `api/` (`explicitApi` + consumer encapsulation gate — Phase 8.1)
- [x] Optional shared `HttpClient` integration test
- [x] Demo app in `shared` connects to echo server and displays events (optional — Phase 7)
- [x] Spotless/ktlint + detekt pass (Phase 8.2–8.3)
- [x] Binary Compatibility Validator committed + `apiCheck` passes (Phase 8.4)
- [x] Dokka HTML generated for `api/` package (Phase 8.5)
- [x] README + CHANGELOG + LICENSE (Phase 8.7)
- [x] CI workflow runs quality + test gates (Phase 8.8)

**Pending (post–v0.1 hardening — see [`gap-code-hardening-plan.md`](./gap-code-hardening-plan.md)):**

- [ ] Maven/CocoaPods publish (G-P1-1 — skipped, library readiness)
- [ ] Package rename to `io.socket` (G-P1-2 — skipped, pre-publish)
- [ ] Platform smoke in default CI on every PR (G-P1-3 — CH1)

---

## 20. Library quality & publish tooling

Build/CI tooling only — **never** runtime dependencies in `:socketio`. Host apps own DI (Koin, etc.); library injects via `SocketOptions` (`Logger`, `HttpClient?`).

| Category | Tool | Scope | Runtime dep? |
|----------|------|-------|--------------|
| API encapsulation | `explicitApi()` | `:socketio` | N/A (compiler) |
| Consumer gate | Negative compile + `rg` scan | `shared` → `:socketio` | No |
| Formatting | Spotless + ktlint | Repo root | No |
| Static analysis | detekt | Repo root / `:socketio` | No |
| API stability | Binary Compatibility Validator | `:socketio` | No |
| API docs | Dokka | `:socketio` | No |
| Flow tests | Turbine | `commonTest` / `jvmTest` only | No |
| Publish metadata | README, CHANGELOG, LICENSE | Repo / `socketio/` | No |
| CI | GitHub Actions | `.github/workflows/` | No |

**Forbidden in `:socketio` runtime:** Koin, Hilt, Timber, Napier, kmp-xlog, third-party Socket.IO clients (see §2).

**Phase:** All tasks in §20 are implemented in LLD Phase 8 (`implementation-plan.md`).

---

## 21. Reconnect pause / resume (v0.2 — gap #12)

**Status:** Design revised (CH3 G-P3-1). Implementation: CH3.1.3 only.

**Problem:** Consumers need to stop reconnect backoff without always tearing down the engine (kmp-socketio #27). **When** to pause varies by app (mobile background, desktop window blur, trading-hours policy, battery saver, etc.) — the library must stay generic, not encode mobile lifecycle or any single product's rules.

### Principle

| Layer | Owns |
|-------|------|
| **Library** | *How* to pause/resume reconnect scheduling (engine primitives) |
| **Consumer app** | *When* to pause/resume (lifecycle, network, business rules) |

The library does **not** subscribe to `ProcessLifecycleOwner`, `scenePhase`, connectivity callbacks, or any injected `Flow` of visibility. No `SocketLifecycleObserver`, no `NetworkObserver`, no `SocketOptions.lifecycleSignals`.

### Options considered

| Option | Shape | Verdict |
|--------|-------|---------|
| A — `SocketLifecycleObserver` | Library callbacks / platform hooks | **Rejected** — library interprets lifecycle |
| B — Docs + `close()` / `openAwait()` only | Zero new API | Valid today; races with in-flight `reconnectJob` on partial teardown |
| C — Injected `Flow<SocketAppVisibility>` | Library collects signals in `ConnectionManager` | **Rejected** — library still owns *when* via signal semantics |
| **D — Imperative primitives (chosen)** | `pauseReconnect()` / `resumeReconnect()` on `SocketClient` | **Locked** — app calls from any policy layer |

### Public API (v0.2)

```kotlin
// SocketClient — consumer invokes when *their* policy says so
fun pauseReconnect()
fun resumeReconnect()
```

KDoc: pauses **reconnect scheduling only**; does not close an active transport. For full teardown use `close()` / `closeAwait()` + `openAwait()`.

No new `SocketOptions` fields. Default behavior unchanged until the app calls `pauseReconnect()`.

### Engine behavior

```text
pauseReconnect()  [on WorkQueue]:
  reconnectPaused = true
  cancel reconnectJob
  transport stays open (if connected)

resumeReconnect()  [on WorkQueue]:
  reconnectPaused = false
  if any namespace wantsConnection() && engine CLOSED → openOnWorker()
```

`scheduleReconnect()` becomes a no-op while `reconnectPaused`. All mutations on `WorkQueue` (§4).

### Consumer recipes (documentation only — not library code)

| Consumer need | App calls |
|---------------|-----------|
| Android `ON_STOP` | `client.pauseReconnect()` |
| Android `ON_START` | `client.resumeReconnect()` |
| iOS `scenePhase == .background` | `pauseReconnect()` |
| Offline (ConnectivityManager / NWPathMonitor) | `pauseReconnect()` when unreachable; `resumeReconnect()` when reachable |
| Full teardown on background | `namespace().close()` or `client.closeAwait()` — existing API |
| Desktop / server worker | App timer or process signal → same primitives |

Each app implements its own policy module; the library stays product-agnostic.

### Tests (CH3.1.3)

| Case | Assert |
|------|--------|
| No `pauseReconnect` call | reconnect unchanged (regression) |
| `pauseReconnect()` during backoff | `reconnectJob` cancelled; no new attempts |
| `resumeReconnect()` after pause | reconnect resumes if namespace `wantsConnection()` |
| Connected + `pauseReconnect()` | transport stays open; no spurious disconnect |

### Out of scope (core `:socketio`)

- Lifecycle / network observation (any platform)
- Foreground service / keep-alive
- `expect` reachability helpers (CH3.1.2 **skipped** — belongs in consumer or optional sibling artifact)
- Automatic token refresh on resume

---

## 22. Connection state recovery (v0.2 spec — gap #13)

**Status:** Spec complete (CH3 G-P3-2). **Implementation:** deferred — won't fix v0.2.

**Full contract:** [`connection-state-recovery-spec.md`](./connection-state-recovery-spec.md)

### What it is

Socket.IO 4.6+ **server opt-in** feature. After a *temporary* disconnect, server may restore:

| Restored | Mechanism |
|----------|-----------|
| Public namespace `sid` | `adapter.restoreSession(pid, offset)` |
| `rooms`, `socket.data` | Persisted session blob |
| Missed server→client events | Packet log replay (non-ack, non-volatile EVENT only) |

Uses private **`pid`** + per-packet **`offset`** (yeast id appended to event JSON array). Distinct from Engine.IO transport `sid` and from client-side `sendBuffer`/`recvBuffer`.

### Server contract (minimal)

```text
Enable:  io = new Server({ connectionStateRecovery: { maxDisconnectionDuration: 120_000 } })

Handshake:  CONNECT ack → {"sid":"…","pid":"…"}
Events:     42["event", …args, "<offset>"]
Reconnect:  CONNECT → {"pid":"…","offset":"<last>","…auth"}
Success:    client.recovered == true (JS); server socket.recovered == true
```

### CMP today vs required

| | Today | If implemented |
|---|-------|----------------|
| CONNECT payload | auth only | + `pid`, `offset` on reconnect |
| CONNECT parse | `sid` only | + `pid`, `recovered` |
| Events | all JSON args → `SocketEvent` | track offset; strip trailing string |
| Public API | `id` | + `recovered: StateFlow<Boolean>` |

### Decision (CH3.2.2)

**Won't fix v0.2** — no consumer server enables `connectionStateRecovery`; existing reconnect + buffers + app resync sufficient. Implement when §22 trigger in spec is met (named server opt-in or product request).

### Out of scope (unchanged)

- Offset on cluster Redis PUB/SUB adapter
- Server-side session storage (server concern)
- Deduplication of replayed events at app layer (consumer concern)

---

## 23. WebTransport (v0.2 experimental — CH3 G-P3-3)

**Status:** Constant + stub factory shipped. **Functional transport:** NO-GO on mobile via Ktor (see spike).

**Spike:** [`webtransport-feasibility-spike.md`](./webtransport-feasibility-spike.md)

| Piece | State |
|-------|-------|
| `Transports.WEBTRANSPORT` | Public constant |
| `SocketOptionsBuilder.experimentalWebTransport` | Default `false`; gates normalization |
| `WebTransportTransport` | Stub — `doOpen` fails fast |
| Ktor OkHttp / Darwin | No WebTransport client API |

**Default:** unchanged (`polling` + `websocket`). `webtransport` in `transports(...)` is silently dropped unless flag enabled (JS parity for unsupported names).
