# KMP Socket.IO Client

Kotlin Multiplatform Socket.IO client for **Android** and **iOS**. Coroutine-first API (`StateFlow`, `Flow`, `suspend`) with an in-house Engine.IO v4 / Socket.IO v5 implementation.

**Package:** `dev.adven.sockit.api`  
**License:** [Apache 2.0](../LICENSE) · **Changelog:** [CHANGELOG.md](../CHANGELOG.md)

## Install

Published to **Maven Central** for Android (AAR), iOS arm64 and simulator arm64 (klib), and JVM (jar). Gradle module metadata resolves the right artifact per target automatically, so one dependency line in `commonMain` covers every target.

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.adventures92:sockit:0.0.1")
        }
    }
}
```

> The Maven coordinate is `io.github.adventures92:sockit`; the runtime package is `dev.adven.sockit.api`. Group and package are independent — the group only names the namespace on Maven Central.

## Why this library?

| | This client | Typical alternatives |
|---|---|---|
| Targets | Android + iOS (KMP) | JS `socket.io-client` wrappers, JVM-only clients |
| API style | Coroutine-first (`Flow`, `suspend`) | Callback `on` / `emit` |
| Protocol | In-house Engine.IO 4 / Socket.IO 5 codec | Third-party protocol deps |
| Dependencies | `kotlinx-*` + Ktor only | Mixed logging / protocol stacks |

Use it when you want a **mobile-native, coroutine-native** Socket.IO client without embedding a JS runtime or pulling in extra protocol libraries.

## Requirements

| | |
|---|---|
| Kotlin | 2.4+ |
| Android | minSdk 24 |
| iOS | arm64, simulator arm64 |
| JVM | 11+ (supported target, not just tests) |
| Server | Engine.IO 4 · Socket.IO 5 |

Only types under `dev.adven.sockit.api` are public. All other packages are internal and not part of the stable contract.

---

## Quick start

`SocketClient.connect` is **suspend**. Collect `Flow`s from a coroutine scope.

```kotlin
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

suspend fun runSocket() = coroutineScope {
    val client = SocketClient.connect(
        "https://example.com",
        socketOptions {
            transports(Transports.WEBSOCKET)
            auth { put("token", "<jwt>") }
        },
    )
    val socket = client.namespace() // default "/"

    launch { socket.connectionState.collect { state -> /* UI */ } }
    launch { socket.errors.collect { error -> /* log / retry */ } }
    launch {
        socket.events("reply").collect { event ->
            // event.name, event.args (Text / Json / Binary)
        }
    }

    socket.openAwait() // suspends until Connected or Failed
    socket.emit("hello", "world")

    // when finished — release the physical connection
    socket.close()
    client.close()
}
```

**Lifecycle:** call `socket.close()` then `client.close()` when the screen or feature is done. Leaking `SocketClient` keeps the engine and reconnect policy alive.

### Coroutine lifecycle (recommended)

Tie collectors to a scope that ends with the screen or feature — cancelling the scope stops collectors; you still must close the socket and client to tear down the engine.

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.Subscribe
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class QuotesViewModel : ViewModel() {
    private var client: SocketClient? = null

    fun start(token: String) {
        viewModelScope.launch {
            val c = SocketClient.connect(
                "https://example.com",
                socketOptions {
                    transports(Transports.WEBSOCKET)
                    auth { put("token", token) }
                },
            )
            client = c
            val socket = c.namespace()

            launch { socket.connectionState.collect { /* UI */ } }
            launch { socket.errors.collect { /* metrics / snackbar */ } }
            launch {
                socket.events("quote").collect { event ->
                    handleQuote(event)
                }
            }

            socket.openAwait()
            socket.emit(Subscribe(buildJsonObject { put("pair", "BTC-INR") }))
        }
    }

    override fun onCleared() {
        client?.namespace()?.close()
        client?.close()
        client = null
    }
}
```

`viewModelScope` cancellation stops `collect` loops but does **not** release the transport — always call `close()` in `onCleared` (or equivalent).

---

## Connection state and errors

### `connectionState: StateFlow<ConnectionState>`

| State | Meaning |
|-------|---------|
| `Disconnected` | Not connected |
| `Connecting` | Handshake / namespace connect in progress |
| `Connected` | Namespace is live |
| `Reconnecting(attempt)` | Engine dropped; retry scheduled |
| `Failed(error)` | Terminal until `open()` again |

Snapshot helpers (no extra `Flow`):

```kotlin
if (socket.isConnected()) socket.emit("ping")
// isDisconnected() is true only for Disconnected — not Connecting / Reconnecting / Failed
```

`SocketClient` exposes the same `connectionState` / `isConnected()` / `isDisconnected()` aggregated across namespaces.

### `errors: Flow<SocketError>`

Hot stream for **non-terminal** operational failures (send failures while connected, parse warnings, transport glitches). Terminal failures also appear on `connectionState` as `Failed`.

| `SocketError` | Typical cause |
|---------------|---------------|
| `Timeout(phase)` | Operation timed out |
| `TlsFailure` | TLS handshake / cert problem |
| `ParseError` | Wire decode failure |
| `PingTimeout` | Heartbeat missed |
| `TransportClosed` | Transport closed unexpectedly |
| `SendFailed` | Write failed (reported on `errors`; does not fail prior `emitAwait`) |
| `ConnectError` | Namespace connect rejected |

Failures raised by the **suspending** members arrive as `SocketException`, which carries the same sealed `SocketError`:

```kotlin
try {
    socket.openAwait()
    socket.emitAwait("hello", "world")
} catch (e: SocketException) {
    when (e.error) {
        is SocketError.ConnectError -> refreshCredentials()
        is SocketError.Timeout -> retryLater()
        else -> report(e.error)
    }
}
```

`SocketException.ConnectionFailed` comes from `openAwait()`; `SocketException.SendFailed` from `emitAwait()` / `emitWithAck()`.

`emit()` is fire-and-forget but reports `SendFailed` on `errors` and triggers reconnect when enabled. `emitAwait()` suspends until the event is **queued** (WorkQueue → engine write buffer); it throws only on **pre-queue** rejection (reserved events, encode errors). Async transport `SendFailed` after enqueue is reported on `errors` only.

### `open()` vs `openAwait()`

| API | Behavior |
|-----|----------|
| `open()` | Non-blocking. Moves to `Connecting`; returns immediately. |
| `openAwait()` | Suspends until `Connected` or `Failed`. Throws on `Failed`. No-op if already connected. |

Use `openAwait()` when the next line depends on a live namespace (first emit, subscription). Use `open()` when you only need to kick off connection and will react via `connectionState`.

---

## Reconnection

When the engine drops unexpectedly (`reconnection = true`, default):

1. Namespace moves to `Reconnecting(attempt)`.
2. Engine retries with exponential backoff (`reconnectionDelayMs` → `reconnectionDelayMaxMs`, `randomizationFactor`).
3. On engine reopen, namespaces that were still **open** (`open()` and not `close()`) automatically send a namespace `CONNECT` packet.
4. `events(name)` collectors stay subscribed — no need to re-register listeners.
5. State returns to `Connected` when the namespace handshake completes.

**You must re-emit server-side subscriptions** (e.g. `Subscribe(...)`) after reconnect if your server does not restore room/channel membership across disconnects. The library does not replay outbound emits.

| Situation | What to do |
|-----------|------------|
| Transient network drop | Usually nothing — engine reconnects; re-subscribe if server requires it |
| `Failed` (auth rejected, ping timeout, TLS) | Terminal for that namespace. Refresh credentials, `close()`, `connect()` with new options, `open()` |
| `reconnectionAttempts` exhausted | Engine stops scheduling retries; call `open()` to try again |
| Intentional shutdown | `close()` sends `"force close"` — **no** auto-reconnect |

```kotlin
import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.Subscribe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

socket.connectionState.collect { state ->
    when (state) {
        is ConnectionState.Connected -> {
            // Safe place to re-emit Subscribe if your server needs it
            socket.emit(Subscribe(buildJsonObject { put("pair", "BTC-INR") }))
        }
        is ConnectionState.Failed -> {
            // Refresh token, tear down, reconnect with new SocketOptions
        }
        else -> Unit
    }
}
```

---

## Receiving events

`socket.events("eventName")` returns a cold `Flow` backed by a hot buffer — start collecting before or after `open()`; events received while disconnected are buffered until the namespace connects.

Each `SocketEvent` has `name` and `args: List<SocketPayload>`:

```kotlin
import dev.adven.sockit.api.SocketPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

socket.events("price").collect { event ->
    when (val payload = event.args.firstOrNull()) {
        is SocketPayload.Text -> handleText(payload.value)
        is SocketPayload.Json -> {
            val obj = payload.element as? JsonObject ?: return@collect
            val pair = obj["pair"]?.jsonPrimitive?.content ?: return@collect
            handlePrice(pair, obj)
        }
        is SocketPayload.Binary -> handleBytes(payload.bytes)
        null -> Unit
    }
}
```

| `SocketPayload` | Source on the wire |
|-----------------|-------------------|
| `Text` | Plain string argument |
| `Json` | Numbers, booleans, objects, arrays, `null` |
| `Binary` | Binary attachment (`ByteString` from `kotlinx-io`) |

Numbers and booleans emitted outbound arrive inbound as `Json` (`JsonPrimitive`). Decode with `kotlinx.serialization` when you know the schema.

---

## Configuration

```kotlin
socketOptions {
    path = "/socket.io/"                    // default
    transports(Transports.POLLING, Transports.WEBSOCKET) // or literals "polling", "websocket"
    // experimentalWebTransport = true  // optional — enables Transports.WEBTRANSPORT stub (not functional yet)
    upgrade = true                          // polling → websocket when server offers it

    auth { put("token", jwt) }              // Socket.IO CONNECT JSON (namespace auth)
    extraHeaders { put("Authorization", listOf("Bearer $jwt")) } // HTTP polling / WS upgrade

    timeoutMs = 20_000
    reconnection = true
    reconnectionAttempts = Int.MAX_VALUE
    reconnectionDelayMs = 1_000
    reconnectionDelayMaxMs = 5_000
    randomizationFactor = 0.5

    multiplex = true   // same origin shares one engine (default)
    forceNew = false   // true → independent engine, not cached

    trustAllCerts = false  // dev only — Android OkHttp; do not use in production
    httpClient = null      // optional shared Ktor client (must install WebSockets)
    logger = Logger.NoOp   // see Logging
}
```

**Transports:** unsupported names are dropped at `build()` (JS parity). An empty list fails fast with `IllegalArgumentException`.

**Auth:** the library does not refresh tokens. On `Failed` / `ConnectError`, refresh in the app, `close()`, and `connect()` with new options.

---

## Emit, subscribe, and ack

### String events

```kotlin
socket.emit("custom", "arg1", 42)
socket.emit("binary", byteString) // kotlinx-io ByteString

suspend fun reliable() {
    socket.emitAwait("important", payload) // completes when queued; pre-queue reject only
}
```

Supported payload types: `String`, `Boolean`, `Number`, `JsonElement`, `ByteString`, `null`. Other types throw at the API boundary.

### Typed commands

`Subscribe` / `Unsubscribe` use **library-fixed** event names — only the JSON body is yours:

```kotlin
import dev.adven.sockit.api.Subscribe
import dev.adven.sockit.api.Unsubscribe
import dev.adven.sockit.api.SocketCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

socket.emit(Subscribe(buildJsonObject { put("pair", "BTC-INR") }))
socket.emit(Unsubscribe(buildJsonObject { put("pair", "BTC-INR") }))
socket.emit(SocketCommand("myEvent", buildJsonObject { put("x", 1) }))
```

### Ack

```kotlin
socket.emitWithAck("echo", "data").collect { ackEvent ->
    // ackEvent.args
}
```

### Multiple namespaces

```kotlin
val root = client.namespace("/")
val private = client.namespace("/private")
root.open()    // non-blocking — observe connectionState
private.openAwait() // or suspend until both are live
```

With `multiplex = true` (default), namespaces on the same `SocketClient` share one physical connection. Use `forceNew = true` or separate `SocketClient` instances for independence.

---

## Logging

Three tiers — consumer output is sanitized (no URLs, tokens, or wire payloads).

| Tier | How | Output |
|------|-----|--------|
| **Release (default)** | Omit `logger` or use `Logger.NoOp` | Silent |
| **App debug** | `Logger.essential { … }` | Lifecycle + error category under single tag `Logger.TAG` (`"SocketIO"`) |
| **Library authors** | Build `:socketio` from source with `-Psocketio.internalLogging=true` | Full wire/FSM detail via internal sink — **not** available in pre-built artifacts |

```kotlin
socketOptions {
    logger = if (isDebugBuild) {
        Logger.essential { tag, level, message, _ ->
            // tag is always Logger.TAG — filter Logcat: adb logcat -s SocketIO
        }
    } else {
        Logger.NoOp
    }
}
```

Observability in production should rely on `connectionState` and `errors`, not log lines.

---

## Shared `HttpClient`

Reuse the app's Ktor client for TLS, timeouts, and interceptors:

```kotlin
socketOptions {
    httpClient = appClient // must have WebSockets plugin installed
}
```

The **app owns** the client — do not close it while sockets are active.

On Android, `trustAllCerts = true` requires the OkHttp engine (used automatically when no shared client is passed). **Dev only.**

> **Before you ship:** the trust-all path installs a no-op `X509TrustManager` and a permissive `HostnameVerifier`. That code is present in the AAR whether or not you set the flag, and Google Play's pre-launch security review flags the pattern. If your release process cannot absorb that finding, pass your own `httpClient` and never set `trustAllCerts`.

## TLS / certificate pinning

Configure TLS on the injected `HttpClient` (OkHttp / Darwin). This library does not ship pinning helpers.

---

## Android R8 / ProGuard

The AAR ships `consumer-rules.pro` (published via AGP consumer keep rules) for minified release builds. Rules cover the public `api` package and the internal `@Serializable` Engine.IO open handshake.

Consumers using R8/ProGuard do not need extra app rules for `:socketio` beyond their own app configuration. Ktor/OkHttp consumer rules apply transitively via `ktor-client-okhttp`.

Verify locally:

```bash
./gradlew :androidApp:assembleRelease
```

---

## Server compatibility

Wire protocol: **Engine.IO v4** + **Socket.IO packet v5** (same as the official `socket.io` npm **4.x** server line).

| `socket.io` server (npm) | Recovery server opt-in | CI smoke | Notes |
|--------------------------|------------------------|----------|-------|
| **4.5.4** | — | `server-compatibility` job | Oldest matrix pin |
| **4.6.2** | off | `server-compatibility` job | Baseline 4.6 |
| **4.6.2** | on (`connectionStateRecovery`) | `server-compatibility` job | Server-side recovery only; [client impl deferred](../docs/connection-state-recovery-spec.md) |
| **4.7.5** | — | `server-compatibility` job | — |
| **4.8.1** | — | `server-compatibility` + default `jvmTest` | Bundled echo server dependency |

Smoke coverage per matrix cell ([`ServerCompatibilitySmokeTest`](src/jvmTest/kotlin/dev/adven/sockit/ServerCompatibilitySmokeTest.kt)): polling handshake, namespace connect, echo round-trip, ack round-trip, WebSocket-only transport.

Local matrix (optional):

```bash
cd socketio/src/jvmTest/resources
docker compose -f docker-compose.server-matrix.yml up -d
# services on ports 3001–3004; default jvmTest still uses npm ci + ephemeral port
docker compose -f docker-compose.server-matrix.yml down
```

Validated with the bundled Node echo server (`socketio/src/jvmTest/resources/socket-server.js`) — written for this repository, implementing only the handlers the Kotlin suites exercise.

---

## Limitations (v0.0.1)

| In scope | Out of scope |
|----------|--------------|
| Android + iOS | Desktop, JS, Wasm |
| Coroutine API | Callback `on` / `emit` API |
| Polling + WebSocket | Functional `webtransport` (experimental stub only) |
| Injectable `Logger` | Third-party log libs as dependencies |
| Optional shared `HttpClient` | Token refresh / credential storage |
| Binary emit + receive (`ByteString`) | Server-side Socket.IO |
| Multiplex + reconnect | Foreground service / lifecycle hooks |

---

## Public API

| Type | Role |
|------|------|
| `SocketClient` | Entry point; multiplex registry |
| `NamespaceSocket` | Per-namespace socket |
| `socketOptions { }` / `SocketOptions` | Immutable configuration snapshot |
| `Transports` | `POLLING`, `WEBSOCKET`, `WEBTRANSPORT` (experimental) |
| `ConnectionState` | Lifecycle states |
| `SocketError` | Sealed error hierarchy |
| `SocketException` | Thrown by suspending members; wraps a `SocketError` |
| `StreamCommand`, `Subscribe`, `Unsubscribe`, `SocketCommand` | Outbound commands |
| `SocketEvent`, `SocketPayload` | Inbound event shape |
| `Logger` | Optional debug sink (`NoOp` default) |

Generate API reference locally:

```bash
./gradlew :socketio:dokkaGeneratePublicationHtml
# output: socketio/build/dokka/html/
```

---

## Contributing

[![socketio-ci](https://github.com/adventures92/Sockit/actions/workflows/socketio-ci.yml/badge.svg)](https://github.com/adventures92/Sockit/actions/workflows/socketio-ci.yml)

### CI jobs

| Job | Runner | When | What |
|-----|--------|------|------|
| `quality-and-test` | `ubuntu-latest` | Every PR / push to `main` / `develop` | spotless, detekt, apiCheck, KMP compile, jvmTest |
| `server-compatibility` | `ubuntu-latest` | Every PR / push | `socket.io` 4.5.4–4.8.1 matrix + recovery-enabled 4.6.2 smoke |
| `platform-smoke` | `macos-latest` | Every PR / push | iOS `iosSimulatorArm64Test` + Node echo server |
| `platform-smoke-android` | `macos-latest` | `workflow_dispatch` only | `connectedDebugAndroidTest` (needs device/emulator) |

```bash
# JVM integration (start echo server first)
cd socketio/src/jvmTest/resources && npm ci && node socket-server.js &
./gradlew :socketio:jvmTest

# Platform smoke (echo server + device/simulator)
./gradlew :socketio:connectedAndroidDeviceTest :socketio:iosSimulatorArm64Test

# Quality gates
./gradlew :socketio:apiCheck spotlessCheck :socketio:detekt
```

See [docs/architecture.md](../docs/architecture.md) for design details.

---

## Acknowledgements

The behaviour model for Engine.IO upgrade sequencing, multiplex-registry lifetime, and reconnect
backoff was studied against [kmp-socketio](https://github.com/HackWebRTC/kmp-socketio) and the
official [socket.io-client](https://github.com/socketio/socket.io-client). Neither is a dependency
and no code is shared — the protocol codec, transports, engine and public API here are original.
Wire behaviour is verified against the
[socket.io-protocol](https://github.com/socketio/socket.io-protocol) test vectors
(`ProtocolConformanceTest`) and a live `socket.io` server matrix.
