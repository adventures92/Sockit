# Sockit Socket.IO Library — Implementation Plan

> **For agentic workers:** One **phase per sub-agent session**. Read/update [`execution-state.md`](./execution-state.md) at start and end. Use the sub-agent prompt template in that file.

**Goal:** Implement a mobile-only KMP Socket.IO client in `Sockit/socketio/`, coroutine-first API, in-house protocol, Ktor transports.

**Architecture:** See [`architecture.md`](./architecture.md).  
**Gap prevention:** See architecture §15 (maps to `kmp-socketio-mobile-gaps.md`).

**Tech stack:** Kotlin 2.4.0 · AGP 9.0.1 · Ktor 3.x · kotlinx-coroutines · kotlinx-serialization-json · kotlinx-io

**Project root:** `<repository root>`

**Gap execution (severity order, status board, remaining steps):** [`gap-execution-plan.md`](./gap-execution-plan.md)

---

## Agent execution protocol

| Step | Action |
|------|--------|
| 1 | Read `docs/execution-state.md` → **Current phase** + granular step list |
| 2 | Cross-check HLD ref in step table against `architecture.md` |
| 3 | Implement steps from **this file** (LLD) for that phase only |
| 4 | Run phase gate from `execution-state.md` (includes **KMP_COMPILE** — see below) |
| 5 | Update `execution-state.md`: step checkboxes, verification output |

**Doc chain:** HLD (`architecture.md`) → LLD (this file) → Execution (`execution-state.md`).

### KMP compile (mandatory for Phases 5–8)

JVM tests do not compile iOS/Android actuals. After any `commonMain` or platform `actual` change:

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
```

Phase 7+ consumer wiring also requires:

```bash
./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
```

Platform smoke (Phase 6.5) additionally compiles test sources:

```bash
./gradlew :socketio:compileAndroidDeviceTest :socketio:compileTestKotlinIosSimulatorArm64
```

---

## File map

| Path | Responsibility |
|------|----------------|
| `socketio/build.gradle.kts` | KMP module: android + ios + jvm(test) |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/SocketClient.kt` | Public entry |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/NamespaceSocket.kt` | Interface |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/SocketOptions.kt` | Options |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/Transports.kt` | Public transport wire-name constants |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/ConnectionState.kt` | Sealed states |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/SocketError.kt` | Sealed public errors |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/Logger.kt` | Logging interface |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/SocketPayload.kt` | Event payload types |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/StreamCommand.kt` | Outbound command interface |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/Subscribe.kt` | Fixed subscribe event + body |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/Unsubscribe.kt` | Fixed unsubscribe event + body |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/api/SocketCommand.kt` | Custom event + body |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/internal/StreamEventNames.kt` | `internal` subscribe/unsubscribe strings |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/internal/WorkQueue.kt` | Single worker |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/internal/EventBus.kt` | Internal listeners |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/internal/SubscriptionHandle.kt` | `off` cleanup |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/connection/ReconnectPolicy.kt` | `internal` backoff |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/protocol/Packets.kt` | `internal` Engine + Socket packet models |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/protocol/EngineIoCodec.kt` | Engine.IO encode/decode |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/protocol/SocketIoCodec.kt` | Socket.IO encode/decode |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/protocol/BinaryAssembler.kt` | Binary reassembly |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/transport/Transport.kt` | Abstract transport |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/transport/PollingTransport.kt` | HTTP long-poll |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/transport/WebSocketTransport.kt` | Ktor WS |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/transport/HttpClientFactory.kt` | Client wrapper |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/transport/TransportFactory.kt` | Creates transports |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/engineio/EngineConnection.kt` | Handshake, heartbeat, upgrade |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/engineio/UpgradeController.kt` | Upgrade FSM |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/socketio/ConnectionManager.kt` | Multiplex, reconnect |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/socketio/SocketClientRegistry.kt` | Multiplex cache + ref count |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/socketio/NamespaceSocketImpl.kt` | Per-namespace logic |
| `socketio/src/commonMain/kotlin/dev/adven/sockit/platform/PlatformHttpClient.kt` | expect decl |
| `socketio/src/androidMain/kotlin/dev/adven/sockit/platform/PlatformHttpClient.android.kt` | OkHttp |
| `socketio/src/iosMain/kotlin/dev/adven/sockit/platform/PlatformHttpClient.ios.kt` | Darwin |
| `socketio/src/jvmMain/kotlin/dev/adven/sockit/platform/PlatformHttpClient.jvm.kt` | CIO (tests) |
| `socketio/src/jvmTest/resources/socket-server.js` | Echo server |
| `socketio/src/androidDeviceTest/kotlin/.../OkHttpSmokeTest.kt` | OkHttp runtime |
| `socketio/src/iosSimulatorArm64Test/kotlin/.../DarwinSmokeTest.kt` | Darwin runtime |
| `gradle/libs.versions.toml` | Version catalog entries |
| `settings.gradle.kts` | `include(":socketio")` |
| `detekt.yml` | detekt rules (Phase 8.3) |
| `.github/workflows/socketio-ci.yml` | CI quality + test gates (Phase 8.8) |
| `socketio/api/socketio.api` | Binary Compatibility Validator dump (Phase 8.4) |
| `CHANGELOG.md`, `LICENSE` | Publish metadata (Phase 8.7) |
| `docs/execution-state.md` | Resumable session state |

---

## Phase 0 — Version catalog & module registration

### Task 0.1: Extend version catalog

**Files:** Modify `gradle/libs.versions.toml`

- [ ] **Step 1:** Add versions and libraries (adjust versions if Gradle resolver reports conflicts):

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

[plugins]
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2:** Register module in `settings.gradle.kts`:

```kotlin
include(":socketio")
```

---

### Task 0.2: Create `socketio` Gradle module

**Files:** Create `socketio/build.gradle.kts`

- [ ] **Step 1:** Create module:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidLibrary {
        namespace = "dev.adven.sockit"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm() // test-only

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.ktor.client.websockets)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            api(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            api(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            api(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidDeviceTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val iosSimulatorArm64Test by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
```

- [ ] **Step 2:** Enable Android instrumented tests in same `build.gradle.kts`:

```kotlin
androidLibrary {
    // ... existing ...
    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.kmp.enableAndroidTest"] = true
}
```

- [ ] **Step 3:** Enable strict API in `socketio/build.gradle.kts`:

```kotlin
kotlin {
    explicitApi()
    // ...
}
```

All non-`api/` types use `internal`. `SocketOptions`, `Subscribe`, `Unsubscribe` use `internal` constructors where construction must go through DSL / companion.

- [ ] **Step 4:** Verify compile (empty module):

```bash
cd <repository root>
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
```

Expected: `BUILD SUCCESSFUL`

---

## Phase 1 — Internal infrastructure & API types

### Task 1.1: WorkQueue

**Files:**
- Create: `socketio/src/commonMain/kotlin/dev/adven/sockit/internal/WorkQueue.kt`
- Test: `socketio/src/commonTest/kotlin/dev/adven/sockit/internal/WorkQueueTest.kt`

- [ ] **Step 1:** Write failing test:

```kotlin
package dev.adven.sockit.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkQueueTest {
    @Test
    fun serializesJobs() = runTest {
        val queue = WorkQueue()
        var value = 0
        val done = CompletableDeferred<Unit>()
        queue.launch { value += 1 }
        queue.launch { value += 2; done.complete(Unit) }
        done.await()
        assertEquals(3, value)
    }
}
```

- [ ] **Step 2:** Run — expect FAIL:

```bash
./gradlew :socketio:jvmTest --tests "dev.adven.sockit.internal.WorkQueueTest"
```

- [ ] **Step 3:** Implement:

```kotlin
package dev.adven.sockit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class WorkQueue {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1, "socketio-worker"),
    )

    fun launch(block: suspend () -> Unit): Job = scope.launch { block() }
}
```

- [ ] **Step 4:** Run test — expect PASS

---

### Task 1.2: Public API types

**Files:**
- Create: `api/Logger.kt`, `api/SocketError.kt`, `api/ConnectionState.kt`, `api/SocketPayload.kt`, `api/SocketEvent.kt`
- Test: `commonTest/.../SocketPayloadTest.kt` (reject unsupported emit types at API boundary later)

- [ ] **Step 1:** Implement:

```kotlin
// Logger.kt
package dev.adven.sockit.api

interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)

    companion object NoOp : Logger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
```

```kotlin
// SocketError.kt
package dev.adven.sockit.api

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

```kotlin
// ConnectionState.kt
package dev.adven.sockit.api

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    data class Failed(val error: SocketError) : ConnectionState
}
```

```kotlin
// SocketPayload.kt
package dev.adven.sockit.api

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement

sealed interface SocketPayload {
    data class Text(val value: String) : SocketPayload
    data class Json(val element: JsonElement) : SocketPayload
    data class Binary(val bytes: ByteString) : SocketPayload
}
```

```kotlin
// SocketEvent.kt
package dev.adven.sockit.api

data class SocketEvent(
    val name: String,
    val args: List<SocketPayload>,
)
```

```kotlin
// SocketPayload.kt — internal encode helper (HLD §10: reject unsupported types)
internal fun encodePayload(value: Any?): SocketPayload = when (value) {
    is String -> SocketPayload.Text(value)
    is JsonElement -> SocketPayload.Json(value)
    is ByteString -> SocketPayload.Binary(value)
    is Boolean, is Number -> SocketPayload.Json(JsonPrimitive(value))
    null -> SocketPayload.Json(JsonNull)
    else -> throw IllegalArgumentException("Unsupported payload type: ${value::class}")
}
```

- [ ] **Step 1.6:** `StreamCommand` outbound types + `internal` event constants:

```kotlin
// api/StreamCommand.kt
interface StreamCommand {
    val eventName: String
    val payload: JsonElement
}

// api/Subscribe.kt
class Subscribe(val payload: JsonElement) : StreamCommand {
    override val eventName: String get() = StreamEventNames.SUBSCRIBE
}

// api/Unsubscribe.kt
class Unsubscribe(val payload: JsonElement) : StreamCommand {
    override val eventName: String get() = StreamEventNames.UNSUBSCRIBE
}

// api/SocketCommand.kt
data class SocketCommand(
    override val eventName: String,
    override val payload: JsonElement,
) : StreamCommand

// internal/StreamEventNames.kt
internal object StreamEventNames {
    const val SUBSCRIBE = "subscribe"
    const val UNSUBSCRIBE = "unsubscribe"
}
```

- [ ] **Step 2:** `SocketPayloadTest` — unsupported type throws `IllegalArgumentException`

---

### Task 1.3: EventBus + SubscriptionHandle

**Files:**
- Create: `internal/EventBus.kt`, `internal/SubscriptionHandle.kt`
- Test: `commonTest/.../EventBusTest.kt`

- [ ] **Step 1:** Test `once` fires once only
- [ ] **Step 2:** Implement dual maps (`on` persistent, `once` single-shot); copy listeners before invoke
- [ ] **Step 3:** `SubscriptionHandle.destroy()` removes listener

---

### Task 1.4: ReconnectPolicy

**Files:**
- Create: `connection/ReconnectPolicy.kt`
- Test: `commonTest/.../ReconnectPolicyTest.kt`

- [ ] **Step 1:** Test delay increases, resets on `reset()`
- [ ] **Step 2:** Implement exponential backoff with jitter:

```kotlin
internal class ReconnectPolicy(
    private val minMs: Long = 1_000,
    private val maxMs: Long = 5_000,
    private val jitter: Double = 0.5,
    private val maxAttempts: Int = Int.MAX_VALUE,
) {
    var attempt: Int = 0
        private set

    fun nextDelayMs(): Long { /* minMs * 2^attempt capped at maxMs + random jitter */ }
    fun reset() { attempt = 0 }
    fun canRetry(): Boolean = attempt < maxAttempts
}
```

---

## Phase 2 — Protocol codec (in-house)

### Task 2.1: Packet models

**Files:** Create `protocol/Packets.kt`

- [ ] **Step 1:** Define sealed types:

```kotlin
// Engine packets
internal sealed interface EnginePacket {
    data class Open(val sid: String, val pingInterval: Int, val pingTimeout: Int, val upgrades: List<String>) : EnginePacket
    data object Close : EnginePacket
    data object Ping : EnginePacket
    data object Pong : EnginePacket
    data class Message(val socket: SocketPacket) : EnginePacket
    data object Upgrade : EnginePacket
    data object Noop : EnginePacket
}

// Socket packets
internal sealed interface SocketPacket {
    data class Connect(val namespace: String, val data: String?) : SocketPacket
    data object Disconnect : SocketPacket
    data class Event(val namespace: String, val data: String) : SocketPacket
    data class Ack(val namespace: String, val id: Int?, val data: String) : SocketPacket
    data class ConnectError(val namespace: String, val data: String) : SocketPacket
    data class BinaryEvent(val namespace: String, val data: String) : SocketPacket
    data class BinaryAck(val namespace: String, val id: Int?, val data: String) : SocketPacket
}
```

---

### Task 2.2: EngineIoCodec

**Files:** Create `protocol/EngineIoCodec.kt`, test `commonTest/.../EngineIoCodecTest.kt`

- [ ] **Step 1:** Golden tests:

```kotlin
@Test
fun encodePing() = assertEquals("2", EngineIoCodec.encode(EnginePacket.Ping))

@Test
fun decodeOpen() {
    val raw = """0{"sid":"abc","upgrades":["websocket"],"pingInterval":25000,"pingTimeout":20000}"""
    val packet = EngineIoCodec.decode(raw) as EnginePacket.Open
    assertEquals("abc", packet.sid)
}
```

- [ ] **Step 2:** Implement `encode`, `decode`, `decodeBatch` (HTTP body may concatenate packets)
- [ ] **Step 3:** Implement `encodeBatch` for polling POST

---

### Task 2.3: SocketIoCodec

**Files:** Create `protocol/SocketIoCodec.kt`, test `commonTest/.../SocketIoCodecTest.kt`

- [ ] **Step 1:** Encode/decode CONNECT, EVENT, ACK, DISCONNECT, BINARY_EVENT, BINARY_ACK for `/` and `/admin`
- [ ] **Step 2:** `encodeEvent(namespace, eventName, jsonPayload)` builds `2/admin,["ev",{...}]`

---

### Task 2.4: BinaryAssembler

**Files:** Create `protocol/BinaryAssembler.kt`, test `commonTest/.../BinaryAssemblerTest.kt`

- [ ] **Step 1:** Port logic: placeholder `-` attachments in JSON + N binary frames → reassembled `ByteString` args
- [ ] **Step 2:** Round-trip test with synthetic BINARY_EVENT packet

---

## Phase 3 — Platform HTTP client

### Task 3.1: expect/actual PlatformHttpClient

**Files:**
- `platform/PlatformHttpClient.kt` (common expect)
- `platform/PlatformHttpClient.android.kt`
- `platform/PlatformHttpClient.ios.kt`
- `platform/PlatformHttpClient.jvm.kt`

- [ ] **Step 1:** commonMain:

```kotlin
internal expect fun createPlatformHttpClient(
    trustAllCerts: Boolean = false,
    external: io.ktor.client.HttpClient? = null,
    configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {},
): io.ktor.client.HttpClient
```

- [ ] **Step 2:** androidMain — `HttpClient(OkHttp)`; if `trustAllCerts`, install permissive `X509TrustManager` + `HostnameVerifier`; always `install(WebSockets) { pingInterval = 20_000 }`
- [ ] **Step 3:** iosMain — `HttpClient(Darwin)` + WebSockets
- [ ] **Step 4:** jvmMain — `HttpClient(CIO)` + WebSockets

---

### Task 3.2: HttpClientFactory + SocketOptions (builder DSL)

**Files:** `transport/HttpClientFactory.kt`, `api/SocketOptions.kt`

- [ ] **Step 1:** `socketOptions { }` DSL + immutable `SocketOptions` (not public data class):

```kotlin
fun socketOptions(block: SocketOptionsBuilder.() -> Unit): SocketOptions

class SocketOptionsBuilder {
    var path: String = "/socket.io/"
    private var transportList = listOf("polling", "websocket")
    fun transports(vararg names: String) { transportList = names.toList() }
    var upgrade: Boolean = true
    private val authMap = mutableMapOf<String, String>()
    fun auth(block: MutableMap<String, String>.() -> Unit) { authMap.block() }
    private val headersMap = mutableMapOf<String, List<String>>()
    fun extraHeaders(block: MutableMap<String, List<String>>.() -> Unit) { headersMap.block() }
    var timeoutMs: Long = 20_000
    var reconnection: Boolean = true
    var reconnectionAttempts: Int = Int.MAX_VALUE
    var reconnectionDelayMs: Long = 1_000
    var reconnectionDelayMaxMs: Long = 5_000
    var randomizationFactor: Double = 0.5
    var multiplex: Boolean = true
    var forceNew: Boolean = false
    var trustAllCerts: Boolean = false
    var httpClient: io.ktor.client.HttpClient? = null
    var logger: Logger = Logger.NoOp
    internal fun build(): SocketOptions
}

class SocketOptions internal constructor(
    val path: String,
    val transports: List<String>,
    val upgrade: Boolean,
    val auth: Map<String, String>,
    val extraHeaders: Map<String, List<String>>,
    val timeoutMs: Long,
    val reconnection: Boolean,
    val reconnectionAttempts: Int,
    val reconnectionDelayMs: Long,
    val reconnectionDelayMaxMs: Long,
    val randomizationFactor: Double,
    val multiplex: Boolean,
    val forceNew: Boolean,
    val trustAllCerts: Boolean,
    val httpClient: io.ktor.client.HttpClient?,
    val logger: Logger,
)
```

- [ ] **Step 2:** `DefaultHttpClientFactory` returns `options.httpClient` or creates via `createPlatformHttpClient`

---

## Phase 4 — Transport layer

### Task 4.1: Transport base

**Files:** `transport/Transport.kt`

- [ ] States: `INIT, OPENING, OPEN, CLOSING, CLOSED, PAUSED`
- [ ] Abstract `open()`, `send(packets)`, `close()`, `pause(onPause)`
- [ ] Internal `EventBus` events: `open`, `close`, `packet`, `drain`, `error`
- [ ] `uri()` builds `/engine.io/?EIO=4&transport=...&sid=...`
- [ ] Flag `isProbe: Boolean` for upgrade probe transports

---

### Task 4.2: PollingTransport

**Files:** `transport/PollingTransport.kt`, `jvmTest/.../PollingTransportTest.kt`

- [ ] GET poll loop → `EngineIoCodec.decodeBatch`
- [ ] POST send → `EngineIoCodec.encodeBatch`
- [ ] `pause()` waits for in-flight poll + drain
- [ ] `close()` sends `EnginePacket.Close`
- [ ] POST failures → transport `error` event (gap #2)
- [ ] jvmTest against `socket-server.js`

---

### Task 4.3: WebSocketTransport

**Files:** `transport/WebSocketTransport.kt`, `jvmTest/.../WebSocketTransportTest.kt`

- [ ] Ktor `webSocket` session; decode text/binary frames
- [ ] **onOpen rule (gap #3):** call `onOpen()` as first action inside session block after handshake — never check session type
- [ ] Response headers: best-effort via Ktor API; failures must not block open
- [ ] Probe ping: `isProbe=true`; drain callback passes `drainedCount=0` for probe-only sends
- [ ] **Send failures (gap #2):** wrap `send()` in try/catch → emit transport `error` with cause (no silent log-only swallow)
- [ ] jvmTest: connect + receive echo; websocket-only handshake reaches OPEN

---

### Task 4.4: TransportFactory

**Files:** `transport/TransportFactory.kt`

- [ ] `create(name: String, ...): Transport` → `PollingTransport` | `WebSocketTransport`

---

## Phase 5 — Engine connection

### Task 5.1: UpgradeController

**Files:** `engineio/UpgradeController.kt`, `commonTest/.../UpgradeControllerTest.kt`

- [ ] Phases: `IDLE, PROBING, PAUSING_POLL, UPGRADING, DONE`
- [ ] `onProbePong()`, `onPollingPaused()`, `onUpgradeDrained()`, `canSwitchTransport()`

---

### Task 5.2: EngineConnection

**Files:** `engineio/EngineConnection.kt`, `jvmTest/.../EngineConnectionTest.kt`

- [ ] Parse URL → host, port, path, secure
- [ ] All state mutations via `WorkQueue` (HLD §4)
- [ ] `open()` → first transport from `options.transports`
- [ ] `onHandshake` → sid, heartbeat job, optional `probeWebSocket()`
- [ ] `writeBuffer: ArrayDeque<EnginePacket>` — use `removeFirstOrNull()` only
- [ ] `onDrain(transport, count)` — skip buffer accounting when `transport.isProbe && count == 0`
- [ ] **Defensive drain (gap #1):** if `count > writeBuffer.size`, log + return (no throw)
- [ ] Map transport `error` → `SocketError` + `errors` SharedFlow + reconnect schedule
- [ ] `close()` → drain → wait upgrade → force close
- [ ] jvmTest: handshake, ping/pong, polling→websocket upgrade

**Phase 5 gate:**

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm \
  :socketio:jvmTest --tests "dev.adven.sockit.engineio.*"
```

---

## Phase 6 — Socket.IO layer

### Task 6.1: ConnectionManager + SocketClientRegistry

**Files:** `socketio/ConnectionManager.kt`, `socketio/SocketClientRegistry.kt`

- [ ] `ConnectionManager` owns `EngineConnection`, `ReconnectPolicy`, `Map<String, NamespaceSocketImpl>`
- [ ] `SocketClientRegistry`: origin-keyed cache, ref counting, evict on last `close()` (gap #9)
- [ ] Multiplex key: `scheme://host:port` unless `forceNew`
- [ ] Events from engine → route to namespaces
- [ ] `destroy()` when all namespaces closed

---

### Task 6.2: NamespaceSocket interface + NamespaceSocketImpl

**Files:** `api/NamespaceSocket.kt`, `socketio/NamespaceSocketImpl.kt`

- [ ] **Step 1:** `NamespaceSocket` interface (HLD §5):

```kotlin
interface NamespaceSocket {
    val connectionState: StateFlow<ConnectionState>
    val id: StateFlow<String?>
    val errors: Flow<SocketError>
    fun isConnected(): Boolean
    fun isDisconnected(): Boolean
    fun open()
    fun close()
    fun emit(event: String, vararg payloads: Any?)
    fun emit(command: StreamCommand)
    suspend fun emitAwait(event: String, vararg payloads: Any?)
    suspend fun emitAwait(command: StreamCommand)
    fun emitWithAck(event: String, vararg payloads: Any?): Flow<SocketEvent>
    fun events(name: String): Flow<SocketEvent>
    suspend fun openAwait()
}
```

- [ ] **Step 2:** `NamespaceSocketImpl` — `internal class`; `StateFlow`, snapshot helpers, `emit(command)` delegates to string `emit`
- [ ] **Step 3:** `sendBuffer` while disconnected; flush on connect
- [ ] **Step 4:** Reserved events guard (`connect`, `disconnect`, etc.)
- [ ] **Step 5:** `BinaryAssembler` for binary events
- [ ] **Step 6:** `emitAwait()` fails with `SocketError.SendFailed`; fire-and-forget `emit()` emits on `errors`

---

### Task 6.3: SocketClient

**Files:** `api/SocketClient.kt`, `api/NamespaceSocket.kt`

- [ ] `companion object { suspend fun connect(url, options): SocketClient }`
- [ ] Delegates multiplex to `SocketClientRegistry` (not raw static map)
- [ ] `namespace(path)`, `connectionState`, `close()`
- [ ] Suspend helpers: `openAwait()`, `emitAwait()`

---

### Task 6.4: End-to-end JVM test

**Files:** `jvmTest/.../ConnectionIntegrationTest.kt`, copy `socket-server.js` from kmp-socketio reference

- [ ] **Step 1:** Start server:

```bash
cd socketio/src/jvmTest/resources && npm ci && node socket-server.js &
```

- [ ] **Step 2:** Test matrix:

| Case | Assert |
|------|--------|
| Connect + disconnect | `Connected` → `Disconnected` |
| Emit / on echo | Payload round-trip |
| Ack emit | Callback / Flow receives ack |
| Reconnect after server drop | `Reconnecting` → `Connected` |
| websocket-only | `transports = listOf("websocket")` |
| Two namespaces | Single engine, two sockets |
| close → immediate open | No crash (upgrade race #31) |
| `connectionState` exposed | StateFlow transitions |
| `isConnected()` / `isDisconnected()` | Snapshot matches `connectionState.value` |
| `emit(Subscribe)` / `emit(Unsubscribe)` | Correct wire events; constants not importable from app |
| `emit(SocketCommand)` | Custom event + payload |
| `errors` on send failure | `SendFailed` emitted (mock transport or fault injection) |
| Shared `HttpClient` | Injected CIO client connects (HLD §19) |

```bash
./gradlew :socketio:jvmTest
```

**Phase 6 gate:**

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm \
  :socketio:jvmTest
```

---

## Phase 6.5 — Platform smoke tests (gap #5)

> Mandatory before v0.1. Proves OkHttp and Darwin WS paths — not covered by JVM/CIO.

### Task 6.5.1: Android instrumented OkHttp smoke test

**Files:**
- `socketio/src/androidDeviceTest/kotlin/dev/adven/sockit/OkHttpSmokeTest.kt`
- `socketio/src/androidDeviceTest/AndroidManifest.xml` (INTERNET)
- `build.gradle.kts`: `testInstrumentationRunner`, echo server host via `testBuildConfigField` or `10.0.2.2`

**Prerequisite:** Echo server running on host (`node socket-server.js`). Document in test class KDoc.

- [ ] Connect with default transports → `Connected`
- [ ] Emit / receive echo event
- [ ] `transports = listOf("websocket")` only → reaches `Connected` (gap #3)
- [ ] Disconnect cleanly

```bash
# Start echo server first, then:
./gradlew :socketio:connectedDebugAndroidTest
```

---

### Task 6.5.2: iOS simulator Darwin smoke test

**Files:** `socketio/src/iosSimulatorArm64Test/kotlin/dev/adven/sockit/DarwinSmokeTest.kt`

- [ ] Same three cases as 6.5.1 using Darwin `createPlatformHttpClient()`
- [ ] Echo server URL: `localhost` from simulator perspective

```bash
./gradlew :socketio:iosSimulatorArm64Test
```

---

### Task 6.5.3: Chaos — close during upgrade

- [ ] Add to jvmTest **or** Android test: open with upgrade enabled → `close()` while probe in flight → reopen → no crash
- [ ] Mirrors kmp-socketio ut-case-analysis

**Phase 6.5 gate** (echo server on host required for runtime tests):

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm \
  :socketio:compileAndroidDeviceTest :socketio:compileTestKotlinIosSimulatorArm64
# Start echo server, then:
./gradlew :socketio:connectedDebugAndroidTest :socketio:iosSimulatorArm64Test
```

---

## Phase 6.6 — Transport constants & JS-compatible normalization

> **HLD:** §5 SocketOptions (transport names), §11 transports. Align public API with Socket.IO JS: constants for discoverability, string literals still accepted, unsupported names silently filtered at `build()`.

**Goal:** Expose `Transports.POLLING` / `Transports.WEBSOCKET`; normalize transport lists before connect; eliminate runtime `Unknown transport` crashes from typos.

### Task 6.6.1: Public `Transports` object

**Files:** Create `api/Transports.kt`

- [ ] **Step 1:** Add public wire-name constants:

```kotlin
public object Transports {
    public const val POLLING: String = "polling"
    public const val WEBSOCKET: String = "websocket"
}

internal val SUPPORTED_TRANSPORTS: Set<String> = setOf(
    Transports.POLLING,
    Transports.WEBSOCKET,
)
```

- [ ] **Step 2:** Document in KDoc: v0.1 set matches Engine.IO v4 mobile scope; future transports add constants here.

---

### Task 6.6.2: Normalize in `SocketOptionsBuilder.build()`

**Files:** Modify `api/SocketOptions.kt`

- [ ] **Step 1:** Keep `fun transports(vararg names: String)` — accepts `Transports.*` or literals.
- [ ] **Step 2:** On `build()`, normalize:

```kotlin
internal fun normalizeTransports(raw: List<String>, logger: Logger): List<String> {
    val normalized = raw.filter { it in SUPPORTED_TRANSPORTS }
    if (normalized.size < raw.size) {
        logger.debug("SocketOptions", "Dropped unsupported transports: ${raw - normalized.toSet()}")
    }
    require(normalized.isNotEmpty()) {
        "No transports available — supported: ${SUPPORTED_TRANSPORTS.joinToString()}"
    }
    return normalized
}
```

- [ ] **Step 3:** Default remains `listOf(Transports.POLLING, Transports.WEBSOCKET)`.
- [ ] **Step 4:** `SocketOptions.transports` stores **normalized** list only.

**Tests:** `commonTest/.../SocketOptionsTest.kt`

| Case | Expected |
|------|----------|
| Default | `["polling", "websocket"]` |
| `transports(Transports.WEBSOCKET)` | `["websocket"]` |
| `transports("polling", "websocket")` | same as constants |
| `transports("foo", Transports.POLLING)` | `["polling"]` |
| `transports("foo")` | `IllegalArgumentException` at `build()` |
| Order preserved | `transports(WEBSOCKET, POLLING)` → `["websocket", "polling"]` |

---

### Task 6.6.3: Engine + factory alignment

**Files:** `engineio/EngineConnection.kt`, `transport/TransportFactory.kt`

- [ ] **Step 1:** `EngineConnection.openOnWorker()` — use `options.transports.first()` (non-empty guaranteed by build); remove `IllegalStateException("No transports configured")` path or keep as internal assert.
- [ ] **Step 2:** `filterUpgrades()` — intersect server handshake strings with `options.transports` (already normalized); ignore unknown server offers.
- [ ] **Step 3:** `TransportFactory` — keep defensive `IllegalArgumentException` for internal misuse only (should not reach consumers after normalization).
- [ ] **Step 4:** Do **not** add `tryAllTransports` in v0.1 (JS v4.8 option — defer).

---

### Task 6.6.4: Update call sites & examples

**Files:** tests, smoke cases, doc examples

- [ ] Prefer `Transports.*` in `PlatformSmokeTestCases`, integration tests (literals still covered by normalization tests).
- [ ] No behavior change for valid existing configs.

---

**Phase 6.6 gate:**

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm \
  :socketio:jvmTest --tests "dev.adven.sockit.api.*"
```

---

## Phase 7 — Demo app wiring (optional for v0.1)

### Task 7.1: Depend on library from `shared`

**Files:** Modify `shared/build.gradle.kts`

- [ ] Add `implementation(project(":socketio"))` in `commonMain`
- [ ] Simple Compose screen: connect button, log `connectionState`, subscribe to `echoBack`

**Phase 7 gate:**

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm \
  :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
```

Plus manual smoke on Android/iOS (optional for v0.1).

> **Deferred:** Android lifecycle helper (gap #12) — out of scope v0.1 per architecture §16.

---

## Phase 8 — Library quality & publish readiness

> **Mandatory before v0.1 tag.** Build/CI tooling only — **never** add detekt, ktlint, Dokka, or BCV as `:socketio` runtime `implementation` deps. DI (Koin, etc.) stays in host apps; library wires via `SocketOptions` only (HLD §20).

**Prerequisite:** `shared` (or any consumer module) depends on `implementation(project(":socketio"))` — Phase 7.1 or a minimal dependency added solely for the encapsulation gate.

### Task 8.1: Consumer encapsulation compile gate (mandatory)

> `internal` visibility is enforced only across Gradle module boundaries. Cannot compile-check from `:socketio` alone.

**Files:** `shared/build.gradle.kts` (dependency), temporary `InternalLeakCheck.kt` (delete after gate)

- [ ] **Step 1:** Ensure `shared` depends on `:socketio` (`commonMain.dependencies { implementation(project(":socketio")) }`)

- [ ] **Step 2:** Negative compile check — temporary scratch file in `shared` (delete after gate):

```kotlin
// shared/src/commonMain/kotlin/.../InternalLeakCheck.kt — must NOT compile
import dev.adven.sockit.internal.StreamEventNames

@Suppress("unused")
private val leak = StreamEventNames.SUBSCRIBE
```

```bash
./gradlew :shared:compileKotlinJvm
# Expected: BUILD FAILED — cannot access 'StreamEventNames': it is internal
```

Remove `InternalLeakCheck.kt` once confirmed.

- [ ] **Step 3:** Permanent static scan (CI-friendly):

```bash
rg 'socketdemo\.socketio\.(internal|protocol|transport|engineio|socketio|connection|platform)' shared/src
# Expected: no matches
```

- [ ] **Step 4:** Positive check — `Subscribe` / `Unsubscribe` wire names covered by `:socketio` unit tests (`StreamCommandTest`); consumer uses `emit(Subscribe(...))` only.

---

### Task 8.2: Spotless + ktlint (repo root — formatting)

**Files:** `build.gradle.kts` (root), `gradle/libs.versions.toml`

- [ ] **Step 1:** Add to version catalog:

```toml
[versions]
spotless = "7.0.2"
ktlint = "1.5.0"

[plugins]
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

- [ ] **Step 2:** Apply at root `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.spotless) apply false
}
subprojects {
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("**/*.kt")
            targetExclude("**/build/**")
            ktlint(libs.versions.ktlint.get()).editorConfigOverride(
                mapOf("android" to "true"),
            )
        }
        kotlinGradle {
            target("**/*.gradle.kts")
            targetExclude("**/build/**")
            ktlint(libs.versions.ktlint.get())
        }
    }
}
```

- [ ] **Step 3:** Verify:

```bash
./gradlew spotlessCheck
./gradlew spotlessApply   # fix formatting once before committing config
```

---

### Task 8.3: detekt (repo root — static analysis)

**Files:** `build.gradle.kts` (root), `detekt.yml` (root), `gradle/libs.versions.toml`

- [ ] **Step 1:** Add to version catalog:

```toml
[versions]
detekt = "1.23.8"

[plugins]
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

- [ ] **Step 2:** Apply to `:socketio` (and optionally all Kotlin subprojects):

```kotlin
// socketio/build.gradle.kts
plugins {
    alias(libs.plugins.detekt)
}
detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}
```

- [ ] **Step 3:** Create `detekt.yml` with project baselines (complexity, naming, coroutines). Disable `detekt-formatting` rules that overlap Spotless.

- [ ] **Step 4:** Verify:

```bash
./gradlew :socketio:detekt
```

---

### Task 8.4: Binary Compatibility Validator (API stability)

**Files:** `socketio/build.gradle.kts`, `socketio/api/socketio.api` (generated)

- [ ] **Step 1:** Add plugin to version catalog:

```toml
[plugins]
binaryCompatibilityValidator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version = "0.17.0" }
```

- [ ] **Step 2:** Apply on `:socketio`:

```kotlin
plugins {
    alias(libs.plugins.binaryCompatibilityValidator)
}
```

- [ ] **Step 3:** Generate and commit initial API dump:

```bash
./gradlew :socketio:apiDump
# Commit socketio/api/socketio.api
```

- [ ] **Step 4:** CI must run `./gradlew :socketio:apiCheck` — fails on accidental public API changes.

---

### Task 8.5: Dokka (API reference)

**Files:** `socketio/build.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1:** Add Dokka plugin to catalog; apply on `:socketio`.

- [ ] **Step 2:** Configure to document `dev.adven.sockit.api` package only (exclude `internal` packages).

- [ ] **Step 3:** Verify:

```bash
./gradlew :socketio:dokkaHtml
# Output: socketio/build/dokka/html/
```

---

### Task 8.6: Turbine (test-only — Flow assertions)

**Files:** `gradle/libs.versions.toml`, `socketio/build.gradle.kts`

- [ ] Add `turbine` to catalog; `commonTest` + `jvmTest` `implementation` only.

- [ ] Use in at least one test covering `connectionState` or `errors` Flow (Phase 6 tests may already exist — add Turbine if missing).

```toml
[versions]
turbine = "1.2.0"

[libraries]
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
```

---

### Task 8.7: Publish artifacts (README, CHANGELOG, LICENSE)

**Files:** `socketio/README.md`, `CHANGELOG.md`, `LICENSE`

- [ ] **README** — public API examples (`StreamCommand`, encapsulation), `socketOptions` DSL, exhaustive public type list, `SocketError` variants, shared `HttpClient` requirements (WebSockets plugin), server compatibility matrix (EIO 4 / Socket.IO 5), running jvmTest + platform smoke + echo server, TLS / cert pinning patterns.

- [ ] **CHANGELOG** — Keep a Changelog format; document v0.1.0 scope.

- [ ] **LICENSE** — Apache 2.0 or org-standard license file at repo root.

---

### Task 8.8: CI workflow (GitHub Actions)

**Files:** `.github/workflows/socketio-ci.yml`

- [ ] **Step 1:** Create workflow triggered on PR/push to `main`/`develop`:

| Job step | Command |
|----------|---------|
| Format | `./gradlew spotlessCheck` |
| Static analysis | `./gradlew :socketio:detekt` |
| API stability | `./gradlew :socketio:apiCheck` |
| Unit + integration | `./gradlew :socketio:jvmTest` |
| Compile Android | `./gradlew :socketio:compileAndroidMain` |
| Compile JVM | `./gradlew :socketio:compileKotlinJvm` |
| Compile iOS | `./gradlew :socketio:compileKotlinIosSimulatorArm64` |
| Encapsulation scan | `rg 'socketdemo\.socketio\.(internal\|protocol\|…)' shared/src` → empty |
| Forbidden deps | `rg "hildan\|kmp-xlog\|socket\.io-client" socketio/` → empty |

- [ ] **Step 2:** Platform smoke jobs (OkHttp + Darwin) as manual/scheduled or `workflow_dispatch` until echo-server CI infra exists.

**Phase 8 gate (local):**

```bash
./gradlew spotlessCheck :socketio:detekt :socketio:apiCheck \
  :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm \
  :socketio:jvmTest
rg 'socketdemo\.socketio\.(internal|protocol|transport|engineio|socketio|connection|platform)' shared/src
rg "hildan|kmp-xlog|socket\.io-client" socketio/
```

---

## Verification checklist (pre-release)

| Check | Command |
|-------|---------|
| Protocol unit tests | `./gradlew :socketio:jvmTest --tests "*Codec*"` |
| Full integration | `./gradlew :socketio:jvmTest` |
| Android OkHttp smoke | `./gradlew :socketio:connectedDebugAndroidTest` |
| iOS Darwin smoke | `./gradlew :socketio:iosSimulatorArm64Test` |
| Android compile | `./gradlew :socketio:compileAndroidMain` |
| iOS compile | `./gradlew :socketio:compileKotlinIosSimulatorArm64` |
| JVM compile | `./gradlew :socketio:compileKotlinJvm` |
| No JDK21 List APIs | `rg "removeFirst\\(\\)|removeLast\\(\\)" socketio/src` → empty in commonMain |
| No forbidden deps | `rg "hildan|kmp-xlog|socket\\.io-client" socketio/` → empty |
| No public leakage | `explicitApi` + consumer gate (Phase 8.1): `shared` cannot import `internal.*` |
| Formatting | `./gradlew spotlessCheck` |
| Static analysis | `./gradlew :socketio:detekt` |
| API stability | `./gradlew :socketio:apiCheck` |

---

## Risk register

| Risk | Mitigation |
|------|------------|
| Protocol bugs | Golden tests before network tests |
| Upgrade race | `UpgradeController` + dedicated jvmTest |
| Android API &lt; 35 | No `removeFirst()` on JVM lists |
| TLS dev certs | OkHttp actual only on Android |
| Send failure swallowed | Transport error → `SocketError.SendFailed` on `errors` Flow |
| WS onOpen platform bug | Phase 4.3 rule + Phase 6.5 websocket-only tests |
| Ktor version clash | Confirm catalog versions before Task 0.2 compile |
| Binary edge cases | `BinaryAssembler` tests from Phase 2 |

---

## Estimated effort

| Phase | Days |
|-------|------|
| 0–1 Scaffold + infra | 1 |
| 2 Protocol | 2 |
| 3–4 Platform + transports | 2 |
| 5 Engine | 2 |
| 6 Socket.IO + e2e | 2 |
| 6.5 Platform smoke | 1 |
| 6.6 Transport constants | 0.25 |
| 7 Demo app (optional) | 0.5 |
| 8 Library quality & publish | 1.5 |
| **Total** | **~12.25 days** |

---

## Gap coverage (kmp-socketio-mobile-gaps)

| Gap | Requirement | Plan task |
|-----|-------------|-----------|
| #1 | Upgrade/drain FSM + defensive onDrain | 5.1, 5.2, 6.4, 6.5.3 |
| #2 | Send failure propagation | 4.2, 4.3, 5.2, 6.2 |
| #3 | WS onOpen unconditional | 4.3, 6.5 |
| #4 | Coroutine API + SocketPayload | 1.2, 6.2, 6.3 |
| #5 | OkHttp + Darwin runtime tests | 6.5 |
| #6 | Sealed SocketError | 1.2, 5.2, 6.2 |
| #7 | No hildan/xlog; injectable Logger | 0.2, 1.2, 2.x |
| #8 | WorkQueue single worker | 1.1 |
| #9 | Explicit multiplex registry | 6.1, 6.3 |
| #10 | Protocol compatibility docs | 8.7 |
| #11 | TLS / HttpClient injection | 3.1, 3.2, 8.7 |
| #12 | Lifecycle observer | **Deferred** |
| #13 | Connection state recovery | **Deferred** |

---

## Self-review (spec coverage)

| Requirement | Task |
|-------------|------|
| Mobile-only KMP | 0.2 |
| In-house protocol | 2.x |
| Coroutine public API | 1.2, 6.2, 6.3 |
| Transport constants (JS parity) | 6.6 |
| kmp-socketio issue prevention | 1.1, 3.1, 5.1, 5.2, 6.4, 6.5 |
| Shared HttpClient | 3.2 |
| Injectable Logger | 1.2 |
| Public connection state + errors | 1.2, 6.2 |
| Tests | 1–6, 6.5 |
| Resumable execution | `execution-state.md` (HLD→LLD steps) |
| Library quality tooling | 8.2–8.8 (detekt, Spotless, BCV, Dokka, CI) |
| Consumer encapsulation gate | 8.1 |
