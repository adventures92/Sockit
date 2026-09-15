# Socket.IO Library — Execution Plan (HLD → LLD)

> **Resume here.** Single execution tracker — must stay in sync with [`architecture.md`](./architecture.md) (HLD) and [`implementation-plan.md`](./implementation-plan.md) (LLD).

| Doc | Role |
|-----|------|
| `architecture.md` | **HLD** — what & why |
| `implementation-plan.md` | **LLD** — files, APIs, algorithms, test specs |
| `gap-execution-plan.md` | **Gap plan** — severity order, design intent, proof matrix, remaining steps |
| `gap-remediation-plan.md` | **Gap remediation** — verification gaps A–D (G1→G4 + G2b); **complete** 2026-06-18 |
| `gap-code-hardening-plan.md` | **Code hardening** — post-review CI/contract/encapsulation gaps (CH0–CH3) |
| `execution-state.md` | **Execution** — granular steps, gates, session state |

**Project root:** `<repository root>`

---

## Session protocol

1. Read **Overall status** → run only **Current phase** tasks below.
2. Complete steps in order; run **Step verify** after each step.
   - Any step touching `commonMain` / platform `actual` → run **KMP_COMPILE** (below) before marking done.
3. Run **Phase gate** when all phase steps are `[x]` (**KMP_COMPILE** + phase-specific tests — JVM tests alone are not enough).
4. Update checkboxes, **Last verification**, **Overall status**.
5. Stop — one phase per sub-agent session.

### KMP compile gate (mandatory)

JVM-only `jvmTest` does **not** compile iOS/Android actuals. Run after every `commonMain` or platform change.

| Alias | Command |
|-------|---------|
| **KMP_COMPILE** | `./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm` |
| **SHARED_COMPILE** | `./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64` |
| **KMP_TEST_COMPILE** | `./gradlew :socketio:compileAndroidDeviceTest :socketio:compileTestKotlinIosSimulatorArm64` |

### Sub-agent prompt

```text
Sockit socketio — Phase {N} only.

Read:
- docs/execution-state.md (Current phase + step list)
- docs/implementation-plan.md (Phase {N})
- docs/architecture.md (HLD refs in step table)

Rules: TDD where specified · **KMP_COMPILE** on commonMain changes · phase gate must pass · update execution-state.md · no next phase.
```

---

## Overall status

| Field | Value |
|-------|-------|
| **Current phase** | `8` complete · `7` complete |
| **Last updated** | 2026-06-18 |
| **Last agent** | cursor |
| **Blockers** | None — v0.1 + gap remediation (G1–G4, G2b) closed |

---

## Gap remediation tracker (verification gaps A–D)

> Source: [`gap-remediation-plan.md`](./gap-remediation-plan.md) · Post–P8 cumulative verification.

| Gap | HLD | Status | Proof |
|-----|-----|--------|-------|
| **A** ParseError non-terminal | §5.1 | **done** | `ProtocolParseException` → `errors`; `ParseErrorIntegrationTest` |
| **B** Outbound binary emit | §10 | **done** | `OutboundEngineMessage`; WS + polling (`BinaryEmitIntegrationTest`) |
| **C** `SocketClient` snapshots | §5 | **done** | `isConnected()` / `isDisconnected()`; `apiCheck` |
| **D** Dokka `api/` filter | §20 | **done** | `dokkaGeneratePublicationHtml` — `dev.adven.sockit.api` only |

---

## Gap execution tracker (screenshot severity × implementation)

> Source: gap analysis screenshots + HLD §15. **Functional gaps #1–#3, #9, P6.6** are implemented in code; **P6.5** proves #3/#5 on OkHttp/Darwin.

| Gap | Severity (prod) | Status | Proof |
|-----|-----------------|--------|-------|
| **#3** WS `onOpen` unconditional | Production-breaking (ws-only) | **done** | `WebSocketTransport.kt:42-43`; `websocketOnlyTransport` integration + smoke cases |
| **#2** `SendFailed` on `errors` | Production-breaking (silent data loss) | **done** | Transport `error` events → `EngineConnection` → `NamespaceSocketImpl.errors`; `ConnectionIntegrationTest.errorsFlow*` |
| **#1** Defensive `onDrain` + upgrade FSM | Intermittent (upgrade/reconnect) | **done** | `EngineDrainAccounting.kt`, `UpgradeController`, `UpgradeChaosTest`, `closeThenImmediateOpenDoesNotCrash` |
| **#9** Multiplex registry + ref count | Slow burn (lifecycle leaks) | **done** | `SocketClientRegistry.kt`; `forceNewBypassesMultiplexCache`, `multiplexRefCount*` integration |
| **P6.6** `Transports` + normalization | Moderate (config footguns) | **done** | `Transports.kt`, `normalizeTransports`, `SocketOptionsTest` (8 cases) |
| **Upgrade chaos test** | Verification (not a gap) | **done** | `UpgradeChaosTest.kt` |
| **#4** Coroutine API | DX only | **done** | Phase 1.2 + 6.2 (`StateFlow`, `Flow`, `emitAwait`) |
| **#5** JVM-only tests | Detection gap | **done** | P6.5 rerun 2026-06-18: OkHttp 3/3 + Darwin 3/3 with live echo server |
| **#6** Sealed `SocketError` | UX/diagnostics | **done** | `api/SocketError.kt` + error mapping in engine/namespace |
| **#8** `WorkQueue` single worker | Prevents races (#1 cousin) | **done** | `internal/WorkQueue.kt` |

### Remaining before v0.1 tag

| Item | Phase | Status |
|------|-------|--------|
| OkHttp + Darwin smoke with live echo server | 6.5 rerun | **done** |
| Consumer encapsulation gate (`shared` → `:socketio`) | 8.1 | **done** |
| Spotless, detekt, BCV, Dokka, CI | 8.2–8.8 | **done** |
| Demo Compose screen (optional) | 7 | **done** |

### Ordered execution (no hit-and-trial — dependency-first)

```text
1. P6.5 rerun          — echo server + device/sim (confirms #3/#5 on real stacks)
2. P8.0.1 + P8.1       — shared depends on :socketio; negative compile gate; remove kmp-socketio dep
3. P8.2 → P8.4         — Spotless → detekt → BCV (format before API freeze)
4. P8.5 → P8.6         — Dokka → Turbine (docs + Flow test polish)
5. P8.7 → P8.8         — README/CHANGELOG/LICENSE → CI workflow
6. P7 (optional)       — Compose demo after P8.1 (reuse consumer wiring)
```

**Proactive rules for Phase 8:** apply Spotless once before BCV `apiDump`; run **KMP_COMPILE** after any `commonMain` change; do not add runtime deps; CI platform smoke stays `workflow_dispatch` until echo-server infra exists.

---

## HLD → Phase map

| HLD (`architecture.md`) | Phase |
|---------------------------|-------|
| §2 Locked decisions, §3 layout, §18 deps | 0 |
| §4 Threading (`WorkQueue`), §4.1 encapsulation, §5 API types, §5.1 `SocketError` | 1 |
| §10 Protocol (EIO4 + SIO5 + binary) | 2 |
| §8 Shared HttpClient, §12 Platform clients | 3 |
| §11 Transport behavior | 4 |
| §11 Engine + Upgrade FSM, §4 drain safety | 5 |
| §5 Public API, §6 Multiplex/registry, §5.1 errors | 6 |
| §14 Android/iOS runtime tests, §11 WS onOpen | 6.5 |
| §5 `Transports` constants, JS-style transport normalization | 6.6 |
| §3 demo app | 7 (optional) |
| §4.1 encapsulation gate, §20 library quality tooling, §19 success criteria | 8 |

---

## Phase tracker

| Phase | Name | Status | Phase gate |
|-------|------|--------|------------|
| 0 | Scaffold | `complete` | `./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm` |
| 1 | Infra & API types | `complete` | `./gradlew :socketio:jvmTest --tests "dev.adven.sockit.internal.*" --tests "dev.adven.sockit.connection.*"` |
| 2 | Protocol codec | `complete` | `./gradlew :socketio:jvmTest --tests "dev.adven.sockit.protocol.*"` |
| 3 | Platform HTTP | `complete` | **KMP_COMPILE** |
| 4 | Transport layer | `complete` | **KMP_COMPILE** + `./gradlew :socketio:jvmTest --tests "*Transport*"` |
| 5 | Engine connection | `complete` | **KMP_COMPILE** + `./gradlew :socketio:jvmTest --tests "dev.adven.sockit.engineio.*"` |
| 6 | Socket.IO + JVM e2e | `complete` | **KMP_COMPILE** + `./gradlew :socketio:jvmTest` |
| 6.5 | Platform smoke | `complete` | **KMP_COMPILE** + **KMP_TEST_COMPILE** + echo server + `./gradlew :socketio:connectedAndroidDeviceTest` + `./gradlew :socketio:iosSimulatorArm64Test` |
| 6.6 | Transport constants | `complete` | **KMP_COMPILE** + `./gradlew :socketio:jvmTest --tests "dev.adven.sockit.api.*"` |
| 7 | Demo app | `complete` | **KMP_COMPILE** + **SHARED_COMPILE** + manual smoke (optional) |
| 8 | Library quality & publish | `complete` | `./gradlew spotlessCheck :socketio:detekt :socketio:apiCheck` + **KMP_COMPILE** + `./gradlew :socketio:jvmTest` + encapsulation scan |

---

## Phase 0 — Scaffold

**LLD:** implementation-plan §Phase 0 · **HLD:** §2, §3, §18

| Step | Task | HLD | Action | Step verify |
|------|------|-----|--------|-------------|
| 0.1.1 | Version catalog | §18 | Add coroutines, ktor, serialization, kotlinx-io to `gradle/libs.versions.toml` | file contains `ktor =` |
| 0.1.2 | Version catalog | §18 | Add `kotlinSerialization` plugin alias | file contains `kotlinSerialization` |
| 0.1.3 | Module register | §3 | `settings.gradle.kts` → `include(":socketio")` | grep `include(":socketio")` |
| 0.2.1 | Gradle module | §3 | Create `socketio/build.gradle.kts` (KMP targets + deps per LLD) | file exists |
| 0.2.2 | Android test | §14 | `experimentalProperties["android.experimental.kmp.enableAndroidTest"] = true` | in build.gradle.kts |
| 0.2.3 | Source sets | §14 | `androidInstrumentedTest` + `iosSimulatorArm64Test` deps | in build.gradle.kts |
| 0.2.4 | explicitApi | §4.1 | `socketio/build.gradle.kts` | `explicitApi()` on commonMain |

- [x] 0.1.1 Version catalog libraries
- [x] 0.1.2 Version catalog plugin
- [x] 0.1.3 Register module
- [x] 0.2.1 Create build.gradle.kts
- [x] 0.2.2 Enable Android instrumented test (`withDeviceTest` — AGP 9 DSL)
- [x] 0.2.3 Test source set deps (`androidDeviceTest`)
- [x] 0.2.4 explicitApi strict visibility

---

## Phase 1 — Infra & API types

**LLD:** §Phase 1 · **HLD:** §4, §5, §5.1, §9

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 1.1.1 | WorkQueue TDD | §4 | `internal/WorkQueueTest.kt` | `./gradlew :socketio:jvmTest --tests WorkQueueTest` → FAIL |
| 1.1.2 | WorkQueue impl | §4 | `internal/WorkQueue.kt` | same → PASS |
| 1.2.1 | Logger | §9 | `api/Logger.kt` | compiles |
| 1.2.2 | SocketError | §5.1 | `api/SocketError.kt` | compiles |
| 1.2.3 | ConnectionState | §5 | `api/ConnectionState.kt` | `Failed` uses `SocketError` |
| 1.2.4 | SocketPayload | §10 | `api/SocketPayload.kt` | compiles |
| 1.2.5 | SocketEvent | §5 | `api/SocketEvent.kt` | compiles |
| 1.2.6 | Payload guard | §10 | `api/SocketPayload.kt` + test | reject non-supported types; `encodePayload` internal |
| 1.2.7 | StreamCommand API | §5, §4.1 | `api/StreamCommand.kt`, `Subscribe.kt`, `Unsubscribe.kt`, `SocketCommand.kt`, `internal/StreamEventNames.kt` | constants not public |
| 1.3.1 | EventBus TDD | §4 | `internal/EventBusTest.kt` | `once` fires once → FAIL then PASS |
| 1.3.2 | EventBus | §4 | `internal/EventBus.kt` | test PASS |
| 1.3.3 | SubscriptionHandle | §5.2 | `internal/SubscriptionHandle.kt` | `destroy()` removes listener |
| 1.4.1 | ReconnectPolicy TDD | §11 | `connection/ReconnectPolicyTest.kt` | delay + reset → FAIL then PASS |
| 1.4.2 | ReconnectPolicy | §11 | `connection/ReconnectPolicy.kt` | test PASS |

- [x] 1.1.1 WorkQueue test (fail)
- [x] 1.1.2 WorkQueue impl (pass)
- [x] 1.2.1 Logger
- [x] 1.2.2 SocketError
- [x] 1.2.3 ConnectionState
- [x] 1.2.4 SocketPayload
- [x] 1.2.5 SocketEvent
- [x] 1.2.6 Payload encode guard + test
- [x] 1.2.7 StreamCommand + Subscribe/Unsubscribe + internal constants
- [x] 1.3.1 EventBus test
- [x] 1.3.2 EventBus impl
- [x] 1.3.3 SubscriptionHandle
- [x] 1.4.1 ReconnectPolicy test
- [x] 1.4.2 ReconnectPolicy impl

---

## Phase 2 — Protocol codec

**LLD:** §Phase 2 · **HLD:** §10

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 2.1.1 | Packet models | §10 | `protocol/Packets.kt` | EIO 0–6 + SIO 0–6 incl. BINARY_EVENT/ACK |
| 2.2.1 | EngineIoCodec TDD | §10 | `protocol/EngineIoCodecTest.kt` | golden ping + open → FAIL |
| 2.2.2 | EngineIoCodec | §10 | `protocol/EngineIoCodec.kt` | encode/decode/decodeBatch/encodeBatch → PASS |
| 2.3.1 | SocketIoCodec TDD | §10 | `protocol/SocketIoCodecTest.kt` | CONNECT/EVENT/ACK `/` + `/admin` |
| 2.3.2 | SocketIoCodec | §10 | `protocol/SocketIoCodec.kt` | tests PASS |
| 2.4.1 | BinaryAssembler TDD | §10 | `protocol/BinaryAssemblerTest.kt` | placeholder + frames → FAIL |
| 2.4.2 | BinaryAssembler | §10 | `protocol/BinaryAssembler.kt` | round-trip PASS |

- [x] 2.1.1 Packet models (incl. binary packet types)
- [x] 2.2.1 EngineIoCodec tests
- [x] 2.2.2 EngineIoCodec impl
- [x] 2.3.1 SocketIoCodec tests
- [x] 2.3.2 SocketIoCodec impl
- [x] 2.4.1 BinaryAssembler tests
- [x] 2.4.2 BinaryAssembler impl

---

## Phase 3 — Platform HTTP client

**LLD:** §Phase 3 · **HLD:** §8, §12

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 3.1.1 | expect decl | §12 | `platform/PlatformHttpClient.kt` | expect fun compiles |
| 3.1.2 | Android actual | §12 | `platform/PlatformHttpClient.android.kt` | OkHttp + WebSockets + trustAllCerts |
| 3.1.3 | iOS actual | §12 | `platform/PlatformHttpClient.ios.kt` | Darwin + WebSockets |
| 3.1.4 | JVM actual | §12 | `platform/PlatformHttpClient.jvm.kt` | CIO + WebSockets |
| 3.2.1 | SocketOptions DSL | §5 | `api/SocketOptions.kt` | `socketOptions { }` → immutable snapshot |
| 3.2.2 | HttpClientFactory | §8 | `transport/HttpClientFactory.kt` | shared client or platform default |

- [x] 3.1.1 expect PlatformHttpClient
- [x] 3.1.2 Android actual
- [x] 3.1.3 iOS actual
- [x] 3.1.4 JVM actual
- [x] 3.2.1 SocketOptions DSL + builder
- [x] 3.2.2 HttpClientFactory

---

## Phase 4 — Transport layer

**LLD:** §Phase 4 · **HLD:** §11, gap #2, #3

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 4.1.1 | Transport base | §11 | `transport/Transport.kt` | states, events, `isProbe`, `uri()` |
| 4.2.1 | PollingTransport | §11 | `transport/PollingTransport.kt` | poll loop, pause, close |
| 4.2.2 | Polling errors | gap #2 | same | POST fail → `error` event |
| 4.2.3 | Polling test | §14 | `jvmTest/.../PollingTransportTest.kt` | echo server PASS |
| 4.3.1 | WebSocketTransport | §11 | `transport/WebSocketTransport.kt` | WS session + frames |
| 4.3.2 | WS onOpen rule | gap #3 | same | `onOpen()` first in session block, no type gate |
| 4.3.3 | WS send errors | gap #2 | same | catch send → `error` event |
| 4.3.4 | WS probe drain | §11 | same | probe `drainedCount=0` |
| 4.3.5 | WS test | §14 | `jvmTest/.../WebSocketTransportTest.kt` | websocket-only OPEN |
| 4.4.1 | TransportFactory | §11 | `transport/TransportFactory.kt` | polling \| websocket |

- [x] 4.1.1 Transport base
- [x] 4.2.1 PollingTransport
- [x] 4.2.2 Polling error propagation
- [x] 4.2.3 PollingTransport jvmTest (incl. pause waits for drain)
- [x] 4.3.1 WebSocketTransport
- [x] 4.3.2 onOpen unconditional
- [x] 4.3.3 Send error propagation
- [x] 4.3.4 Probe drain accounting
- [x] 4.3.5 WebSocketTransport jvmTest
- [x] 4.4.1 TransportFactory

---

## Phase 5 — Engine connection

**LLD:** §Phase 5 · **HLD:** §4, §11, gap #1, #2, #6  
**Compile:** **KMP_COMPILE** after every step below.

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 5.1.1 | UpgradeController TDD | §11 | `engineio/UpgradeControllerTest.kt` | test FAIL → **KMP_COMPILE** |
| 5.1.2 | UpgradeController | §11 | `engineio/UpgradeController.kt` | FSM PASS → **KMP_COMPILE** |
| 5.2.1 | EngineConnection | §11 | `engineio/EngineConnection.kt` | uses `WorkQueue` → **KMP_COMPILE** |
| 5.2.2 | Handshake + heartbeat | §11 | same | sid, ping/pong → **KMP_COMPILE** |
| 5.2.3 | writeBuffer + onDrain | §4, gap #1 | same | defensive drain → **KMP_COMPILE** |
| 5.2.4 | Error mapping | §5.1, gap #6 | same | `SocketError` flow → **KMP_COMPILE** |
| 5.2.5 | Upgrade integration | §11 | same | probe → pause → switch → **KMP_COMPILE** |
| 5.2.6 | EngineConnection test | §14 | `jvmTest/.../EngineConnectionTest.kt` | handshake, ping/pong, ping-timeout, upgrade PASS |

- [x] 5.1.1 UpgradeController test
- [x] 5.1.2 UpgradeController impl
- [x] 5.2.1 EngineConnection scaffold + WorkQueue
- [x] 5.2.2 Handshake + heartbeat
- [x] 5.2.3 writeBuffer + defensive onDrain
- [x] 5.2.4 Error mapping to SocketError
- [x] 5.2.5 Upgrade flow
- [x] 5.2.6 EngineConnection jvmTest (handshake, ping/pong, ping-timeout, upgrade)

---

## Phase 6 — Socket.IO layer + JVM e2e

**LLD:** §Phase 6 · **HLD:** §5, §6, §5.1, gap #9  
**Compile:** **KMP_COMPILE** after every step below.

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 6.1.1 | SocketClientRegistry | §6 | `socketio/SocketClientRegistry.kt` | ref count → **KMP_COMPILE** |
| 6.1.2 | ConnectionManager | §6 | `socketio/ConnectionManager.kt` | engine + reconnect → **KMP_COMPILE** |
| 6.2.1 | NamespaceSocket iface | §5 | `api/NamespaceSocket.kt` | public API → **KMP_COMPILE** |
| 6.2.2 | NamespaceSocketImpl | §5 | `socketio/NamespaceSocketImpl.kt` | `emit(StreamCommand)` → **KMP_COMPILE** |
| 6.2.3 | Emit + send buffer | §5.1 | same | SendFailed → **KMP_COMPILE** |
| 6.2.4 | Binary events | §10 | same | BinaryAssembler wired → **KMP_COMPILE** |
| 6.3.1 | SocketClient | §5 | `api/SocketClient.kt` | suspend connect → **KMP_COMPILE** |
| 6.4.1 | Echo server | §14 | `jvmTest/resources/socket-server.js` + package.json | `node socket-server.js` starts |
| 6.4.2 | Integration matrix | §14, §19 | `jvmTest/.../ConnectionIntegrationTest.kt` | all LLD cases PASS |
| 6.4.3 | Shared HttpClient test | §8, §19 | same or dedicated test | inject CIO client PASS |

- [x] 6.1.1 SocketClientRegistry
- [x] 6.1.2 ConnectionManager
- [x] 6.2.1 NamespaceSocket interface
- [x] 6.2.2 NamespaceSocketImpl
- [x] 6.2.3 Emit + send buffer + SendFailed
- [x] 6.2.4 Binary events
- [x] 6.3.1 SocketClient
- [x] 6.4.1 Echo server resources
- [x] 6.4.2 ConnectionIntegrationTest matrix
- [x] 6.4.3 Shared HttpClient integration test

---

## Phase 6.5 — Platform smoke tests

**LLD:** §Phase 6.5 · **HLD:** §14, gap #3, #5  
**Compile:** **KMP_COMPILE** + **KMP_TEST_COMPILE** after every step below.

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 6.5.1 | Android smoke | §14 | `androidInstrumentedTest/.../OkHttpSmokeTest.kt` | **KMP_TEST_COMPILE** → device test PASS |
| 6.5.2 | iOS smoke | §14 | `iosSimulatorArm64Test/.../DarwinSmokeTest.kt` | **KMP_TEST_COMPILE** → sim test PASS |
| 6.5.3 | Chaos close-during-upgrade | gap #1 | jvmTest or Android | close during probe → reopen, no crash |

**Prerequisite:** echo server on host (`10.0.2.2` emulator / `localhost` sim).

- [x] 6.5.1 OkHttpSmokeTest
- [x] 6.5.2 DarwinSmokeTest
- [x] 6.5.3 Chaos upgrade test

---

## Phase 6.6 — Transport constants & JS-compatible normalization

**LLD:** §Phase 6.6 · **HLD:** §5 (`Transports`, SocketOptions transport filtering), §11  
**Compile:** **KMP_COMPILE** after every step below.

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 6.6.1 | `Transports` object | §5 | `api/Transports.kt` | `POLLING`/`WEBSOCKET` public; `SUPPORTED_TRANSPORTS` internal |
| 6.6.2 | Normalize at `build()` | §5 | `api/SocketOptions.kt` | filter unsupported; fail if empty; debug log on drop |
| 6.6.3 | Normalization tests | §5 | `commonTest/.../SocketOptionsTest.kt` | JS parity cases in LLD table → PASS |
| 6.6.4 | Engine alignment | §11 | `engineio/EngineConnection.kt` | first transport connect; upgrade filter unchanged semantics |
| 6.6.5 | Factory defensive guard | §11 | `transport/TransportFactory.kt` | internal-only unknown throw |
| 6.6.6 | Update test call sites | §5 | smoke + integration tests | use `Transports.*` where practical |

- [x] 6.6.1 Public `Transports` constants
- [x] 6.6.2 `SocketOptionsBuilder` normalization
- [x] 6.6.3 SocketOptionsTest (filter + empty fail)
- [x] 6.6.4 EngineConnection uses normalized list
- [x] 6.6.5 TransportFactory internal guard
- [x] 6.6.6 Test call site updates

---

## Phase 7 — Demo app (optional)

**LLD:** §Phase 7 · **HLD:** §3  
**Compile:** **KMP_COMPILE** + **SHARED_COMPILE** after each step.

- [x] 7.1.1 `shared` depends on `:socketio` → **SHARED_COMPILE**
- [x] 7.1.2 Compose screen: connect, `connectionState`, `echoBack` events → **SHARED_COMPILE** + manual smoke

> Consumer encapsulation gate moved to **Phase 8.1** (mandatory before v0.1). Phase 7.1 is a convenient prerequisite but not required if `shared` dependency is added solely for the gate.

---

## Phase 8 — Library quality & publish readiness

**LLD:** §Phase 8 · **HLD:** §4.1, §20, §19  
**Compile:** **KMP_COMPILE** required in phase gate (quality tooling does not replace it).

| Step | Task | HLD | Files | Step verify |
|------|------|-----|-------|-------------|
| 8.0.1 | Consumer dep | §4.1 | `shared/build.gradle.kts` | **SHARED_COMPILE** |
| 8.1.1 | Negative compile gate | §4.1 | temp `InternalLeakCheck.kt` in `shared` | `:shared:compileKotlinMetadata` or **SHARED_COMPILE** → FAIL |
| 8.1.2 | Remove scratch file | §4.1 | delete `InternalLeakCheck.kt` | **SHARED_COMPILE** → PASS |
| 8.1.3 | Static encapsulation scan | §4.1 | CI script | `rg` on `shared/src` → no internal imports |
| 8.2.1 | Spotless + ktlint | §20 | root `build.gradle.kts`, catalog | `./gradlew spotlessCheck` → PASS |
| 8.3.1 | detekt | §20 | `detekt.yml`, `socketio/build.gradle.kts` | `./gradlew :socketio:detekt` → PASS |
| 8.4.1 | Binary Compatibility Validator | §20 | `socketio/build.gradle.kts`, `api/socketio.api` | `./gradlew :socketio:apiCheck` → PASS |
| 8.5.1 | Dokka | §20 | `socketio/build.gradle.kts` | `./gradlew :socketio:dokkaHtml` → HTML output |
| 8.6.1 | Turbine (test-only) | §20 | catalog + `commonTest`/`jvmTest` | used in ≥1 Flow test |
| 8.7.1 | README | §10, §8 | `socketio/README.md` | API + compatibility matrix |
| 8.7.2 | CHANGELOG + LICENSE | §20 | `CHANGELOG.md`, `LICENSE` | files exist |
| 8.8.1 | GitHub Actions CI | §20 | `.github/workflows/socketio-ci.yml` | PR runs quality + test jobs |

- [x] 8.0.1 `shared` depends on `:socketio`
- [x] 8.1.1 Negative compile gate (InternalLeakCheck)
- [x] 8.1.2 Remove scratch file
- [x] 8.1.3 Static encapsulation scan in CI
- [x] 8.2.1 Spotless + ktlint at repo root
- [x] 8.3.1 detekt at repo root / `:socketio`
- [x] 8.4.1 Binary Compatibility Validator + committed `.api` dump
- [x] 8.5.1 Dokka HTML for `api/` package
- [x] 8.6.1 Turbine test dependency
- [x] 8.7.1 README
- [x] 8.7.2 CHANGELOG + LICENSE
- [x] 8.8.1 CI workflow

---

## Verification (pre-release — mirrors architecture §19)

| # | Criterion (HLD §19) | Command |
|---|---------------------|---------|
| V1 | Compiles all targets | **KMP_COMPILE** |
| V2 | JVM integration | `./gradlew :socketio:jvmTest` |
| V3 | Android OkHttp smoke | `./gradlew :socketio:connectedAndroidDeviceTest` |
| V4 | iOS Darwin smoke | `./gradlew :socketio:iosSimulatorArm64Test` |
| V5 | No JDK21 List APIs | `rg "removeFirst\\(\\)|removeLast\\(\\)" socketio/src/commonMain` → empty |
| V6 | No forbidden deps | `rg "hildan|kmp-xlog|socket\\.io-client" socketio/` → empty |
| V7 | No public API leakage | `explicitApi` + Phase 8.1 consumer gate + `./gradlew :socketio:apiCheck` |
| V8 | SendFailed propagated | covered by 6.4.2 matrix |
| V9 | connectionState + errors | covered by 6.4.2 matrix |
| V10 | Formatting (Spotless/ktlint) | `./gradlew spotlessCheck` |
| V11 | Static analysis (detekt) | `./gradlew :socketio:detekt` |
| V12 | API docs (Dokka) | `./gradlew :socketio:dokkaHtml` |

---

## Last verification

```text
CH3 G-P3-4 complete — 2026-06-19 (cursor)
  ServerCompatibilitySmokeTest + CI matrix (socket.io 4.5.4–4.8.1, recovery 4.6.2)
  README compatibility table; docker-compose.server-matrix.yml for local matrix
  CH3 complete (G-P3-1 … G-P3-4 all done)

CH3 G-P3-3 complete — 2026-06-19 (cursor)
  WebTransport constant + experimental flag + stub; feasibility NO-GO spike
  Next: G-P3-4 server compatibility matrix

CH3 G-P3-2 complete — 2026-06-19 (cursor)
  connection-state-recovery-spec.md + HLD §22; won't fix v0.2 (impl deferred)
  Next: G-P3-3 WebTransport

CH3 G-P3-1 complete — 2026-06-19 (cursor)
  pauseReconnect()/resumeReconnect() on SocketClient; ReconnectPauseResumeTest 2/2
  KMP_COMPILE + apiCheck → PASS
  Current phase: CH3 (G-P3-2 … G-P3-4 pending)

CH2 code hardening — 2026-06-19 (cursor)
  gap-code-hardening-plan CH2: api encapsulation, workQueue emitError, EventBufferConfig,
    SocketClient.errors aggregate, Turbine tests, Dokka in CI
  spotlessCheck + detekt + apiCheck + KMP_COMPILE + jvmTest (133/133) + encapsulation scan → PASS
  Current phase: CH3 (gap-code-hardening-plan)

CH1 code hardening — 2026-06-19 (cursor)
  gap-code-hardening-plan CH1: jvmTest stability, platform-smoke iOS on PR, closeAwait API
  jvmTest 3x --rerun-tasks (126/126) + apiCheck + detekt + KMP_COMPILE → PASS
  Current phase: CH2 (gap-code-hardening-plan)

CH0 code hardening — 2026-06-19 (cursor)
  gap-code-hardening-plan CH0: detekt fix, emitAwait Option A contract, HLD §19 sync
  spotlessCheck + detekt + apiCheck + KMP_COMPILE + jvmTest *EmitAwait* → PASS

Prior:
G5 doc sync — 2026-06-18 (cursor)
  architecture-diagrams §5 emit(Subscribe) + §7 emitAwait queue vs async SendFailed
  implementation-plan androidInstrumentedTest → androidDeviceTest
  execution-state gap remediation tracker (A–D) + gap-remediation-plan link

Prior:
  G1–G4 + G2b gap remediation — 2026-06-18 (cursor)
  ParseError, OutboundEngineMessage (WS+polling), SocketClient snapshots, Dokka api/ filter
  KMP_COMPILE + targeted jvmTest + apiCheck + detekt → PASS

Prior:
  P8 + P7 completion — 2026-06-18 (cursor)
  shared → implementation(project(":socketio")); kmp-socketio removed
  TempViewModel rewritten to SocketClient + NamespaceSocket coroutine API
  InternalLeakCheck negative compile gate → FAIL (then deleted)
  spotlessCheck + detekt + apiCheck + dokkaHtml → PASS
  SHARED_COMPILE → PASS
  jvmTest → 103/108 (5 ping-pong/reconnect timeouts with Node echo server)
  SockitScreen: connectionState + event log + connect/subscribe/disconnect
  CI: .github/workflows/socketio-ci.yml

Prior:
  P6.5 runtime rerun — 2026-06-18 (cursor)
  npm install (socketio/src/jvmTest/resources) + node socket-server.js on :3000
  KMP_COMPILE + KMP_TEST_COMPILE → BUILD SUCCESSFUL
  connectedAndroidDeviceTest (Pixel_6a AVD) → 3/3 PASS (cleartext manifest fix for 10.0.2.2 HTTP)
  iosSimulatorArm64Test → 3/3 PASS (connectDefault, emitEcho, websocketOnly / gap #3)

Prior:
  Gap plan session — 2026-06-18 (see docs/gap-execution-plan.md)
  KMP_COMPILE → BUILD SUCCESSFUL
  Unit tests (api, protocol, internal, connection, UpgradeController, EngineDrainAccounting) → PASS
  jvmTest --rerun-tasks (full, no echo server) → 33 failures (network suites; expected without Node)
  P6.5 platform smoke → NOT re-run (echo server + device/sim prereq)

Prior sessions incorporated (f70dbc64 transcript not on disk):
  d8edfbab — Phase 6.6 verification PASS; architecture-diagrams.md Transports in §2/§14
  eb4ac11b — cumulative-verification-p0-p66.md (106 jvmTest with server/env from prior run)
  3ac91b58 — gap execution tracker added to this file
```

**Run integration tests with echo server:**

```bash
cd socketio/src/jvmTest/resources && npm ci && node socket-server.js &
./gradlew :socketio:jvmTest
```

---

## Notes

- Lifecycle / reconnect pause: **done** — `SocketClient.pauseReconnect()` / `resumeReconnect()` (HLD §21); app owns when to call.
- Package naming: placeholder until pre-publish.
- Gap source: internal mobile-gaps analysis → HLD §15.
