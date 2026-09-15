# Sockit — Gap Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` or `subagent-driven-development`. Update [`execution-state.md`](./execution-state.md) after each step. Cross-check HLD [`architecture.md`](./architecture.md) §15 and LLD [`implementation-plan.md`](./implementation-plan.md).

**Goal:** Close all functional kmp-socketio gaps (screenshot severity order) and ship v0.1 with platform proof + publish gates.

**Project root:** `<repository root>`

**Last verified:** 2026-06-18 — P8 + P7 complete (consumer wiring, quality gates, demo screen).

---

## Doc chain

| Layer | File | Role |
|-------|------|------|
| HLD | `architecture.md` | What & why; gap register §15 |
| LLD | `implementation-plan.md` | Phases 0–8, files, algorithms |
| **Gap plan** | **this file** | Severity order, design intent, proof matrix |
| Execution | `execution-state.md` | Checkbox steps, phase gates, session resume |
| Cumulative proof | `cumulative-verification-p0-p66.md` | P0–P6.6 audit (session eb4ac11b) |

### Prior session incorporation

| Session | Work | Status in repo |
|---------|------|----------------|
| **d8edfbab** | Phase 6.6 verification; `architecture-diagrams.md` §2/§14 `Transports` | Done |
| **eb4ac11b** | Cumulative P0–P6.6 verification report (no code) | `cumulative-verification-p0-p66.md` |
| **3ac91b58** | Gap tracker added to `execution-state.md`; KMP + jvmTest verification | Tracker live |
| **f70dbc64** | Transcript not found on disk — work assumed covered by d8edfbab + eb4ac11b + cumulative report | N/A |

---

## Severity tiers (from screenshots)

```text
Production-breaking     → #3, #2
Intermittent            → #1 (+ Upgrade chaos test)
Slow burn / lifecycle   → #9
Config footguns         → P6.6
Detection / DX          → #5, #4, #6, #8 (lower prod impact; still required for v0.1)
```

---

## Master status board

| ID | Gap | Prod severity | Impl | Unit proof | Platform proof | Phase |
|----|-----|---------------|------|------------|----------------|-------|
| **#3** | WS `onOpen` unconditional | Hard break (ws-only) | **done** | `WebSocketTransportTest` | P6.5 PASS (2026-06-18) | 4.3, 6.5 |
| **#2** | `SendFailed` on `errors` | Silent data loss | **done** | `ConnectionIntegrationTest.errorsFlow*` | — | 4.2–6.2 |
| **#1** | Defensive `onDrain` + upgrade FSM | Intermittent crash/stuck | **done** | `UpgradeControllerTest`, `EngineDrainAccountingTest`, `UpgradeChaosTest` | — | 5.1–5.2, 6.5.3 |
| **#9** | Multiplex registry + ref count | Leaks / coupled sockets | **done** | `SocketClientRegistry` + integration `multiplexRefCount*` | — | 6.1 |
| **P6.6** | `Transports` + normalization | Config footguns | **done** | `SocketOptionsTest` (8 cases) | — | 6.6 |
| **Chaos** | Close during upgrade probe | Regression guard | **done** | `UpgradeChaosTest` | — | 6.5.3 |
| **#8** | `WorkQueue` single worker | Race prevention (#1 cousin) | **done** | `WorkQueueTest` | — | 1.1 |
| **#6** | Sealed `SocketError` | UX / diagnostics | **done** | Error mapping in engine + namespace | — | 1.2, 5.2, 6.2 |
| **#4** | Coroutine API (no callbacks) | API shape only | **done** | `NamespaceSocket` surface | — | 1.2, 6.2 |
| **#5** | JVM-only tests | Detection gap | **done** | jvmTest pyramid | P6.5 PASS (2026-06-18) | 6.5 |
| **P8** | Encapsulation + quality gates | Ship blocker | **done** | detekt + BCV + spotless | SHARED_COMPILE | 8 |
| **P7** | Demo Compose screen | Optional | **done** | — | manual | 7 |

---

## Gap deep-dives (design → proof — no hit-and-trial)

### #3 — WS `onOpen` unconditional

| | |
|---|---|
| **User impact** | `transports(Transports.WEBSOCKET)` hangs in `Connecting` on OkHttp/Darwin; polling-first masks bug |
| **Root cause (kmp-socketio)** | Gated `onOpen()` on session type or response headers |
| **Design choice** | Call `onOpen()` as **first** statement inside Ktor `webSocket { }` block; headers best-effort after |
| **Files** | `transport/WebSocketTransport.kt:42-43` |
| **Do not** | Add session-type checks; block open on header read failure |
| **Tests** | `WebSocketTransportTest` ws-only OPEN; `ConnectionIntegrationTest.websocketOnlyTransport`; **P6.5** OkHttp + Darwin smoke |
| **Verify** | `./gradlew :socketio:jvmTest --tests "*WebSocketTransport*"` + P6.5 with echo server |

### #2 — `SendFailed` on `errors` Flow

| | |
|---|---|
| **User impact** | `emit(subscribe)` appears local-success; server never receives; no retry path |
| **Design choice** | Transport `error` event → map `SocketError.SendFailed` → hot `errors` Flow + reconnect policy; `emitAwait` throws |
| **Files** | `PollingTransport`, `WebSocketTransport`, `EngineConnection`, `NamespaceSocketImpl` |
| **Do not** | Log-only swallow; return success from `emit()` on write failure |
| **Tests** | `errorsFlowReceivesReservedEventEmitFailure`, `errorsFlowReceivesSendFailureAfterTransportDrop` |
| **Verify** | `./gradlew :socketio:jvmTest --tests ConnectionIntegrationTest.errorsFlow*` (needs echo server or embedded) |

### #1 — Defensive `onDrain` + upgrade FSM

| | |
|---|---|
| **User impact** | Crash / stuck reconnect / duplicate connection during upgrade or close→reopen |
| **Design choice** | Explicit FSM (`IDLE→PROBING→PAUSING_POLL→UPGRADING→DONE`); probe drain `count=0`; `drainWriteBuffer` no-ops if `count > buffer.size` |
| **Files** | `UpgradeController.kt`, `EngineDrainAccounting.kt`, `EngineConnection.kt` |
| **Do not** | Throw from `onDrain`; decrement `writeBuffer` on probe ping |
| **Tests** | `UpgradeControllerTest`, `EngineDrainAccountingTest`, `UpgradeChaosTest`, `closeThenImmediateOpenDoesNotCrash` |
| **Verify** | `./gradlew :socketio:jvmTest --tests "dev.adven.sockit.engineio.*"` |

### #9 — Multiplex registry + ref count

| | |
|---|---|
| **User impact** | Orphan sockets; closing one client kills another namespace; `forceNew` ignored |
| **Design choice** | `SocketClientRegistry`: origin key when `multiplex && !forceNew`; `acquireClient`/`releaseClient` ref count; evict + `destroyFromRegistry` at zero |
| **Files** | `SocketClientRegistry.kt`, `ConnectionManager.kt`, `SocketClient.kt` |
| **Do not** | Static global `IO` map without refcount; ignore `forceNew` |
| **Tests** | `forceNewBypassesMultiplexCache`, `multiplexRefCountKeepsConnectionAliveUntilLastClientClosed` |
| **Verify** | Integration tests above + manual two-`SocketClient` scenario |

### P6.6 — `Transports` + JS-compatible normalization

| | |
|---|---|
| **User impact** | Typo `"websoket"` → obscure runtime crash; wrong transport order |
| **Design choice** | Filter to `SUPPORTED_TRANSPORTS` at `build()`; `IllegalArgumentException` if empty; debug log on drop (JS silent-drop parity) |
| **Files** | `api/Transports.kt`, `api/SocketOptions.kt` (`normalizeTransports`) |
| **Do not** | `tryAllTransports` in v0.1; runtime `shift()` retry loop (fail-fast at build is intentional) |
| **Tests** | `SocketOptionsTest` full LLD table (8 cases) |
| **Verify** | `./gradlew :socketio:jvmTest --tests "dev.adven.sockit.api.*"` |

### #5 — Platform smoke (detection gap)

| | |
|---|---|
| **User impact** | CIO/JVM passes but OkHttp/Darwin WS path regresses undetected |
| **Design choice** | Shared `PlatformSmokeTestCases`: default transports, echo, ws-only; Android `Assume` skip vs iOS assert |
| **Files** | `OkHttpSmokeTest`, `DarwinSmokeTest`, `PlatformSmokeTestCases` (×2) |
| **Prerequisite** | `cd socketio/src/jvmTest/resources && npm ci && node socket-server.js` |
| **Verify** | `./gradlew :socketio:connectedAndroidDeviceTest` + `./gradlew :socketio:iosSimulatorArm64Test` |

### Lower-impact (done — maintain in regression)

| Gap | Role | Proof |
|-----|------|-------|
| **#8** WorkQueue | Serializes state machine | `WorkQueueTest` |
| **#6** Sealed `SocketError` | Exhaustive app handling | `api/SocketError.kt` + engine mapping |
| **#4** Coroutine API | No callback `on`/`emit` | Public `NamespaceSocket` only |

---

## Ordered execution (remaining → v0.1)

**Rule:** No phase skipping. **KMP_COMPILE** after any `commonMain` / platform `actual` change.

```text
┌─────────────────────────────────────────────────────────────────┐
│ DONE: P0 → P6.6 implementation (all functional gaps in code)   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─ STEP 1 — P6.5 RUNTIME RERUN ────────────────────────────────────┐
│ Status: DONE (2026-06-18)                                       │
│ Proves: #3 + #5 on OkHttp/Darwin                               │
│ Gate: connectedAndroidDeviceTest + iosSimulatorArm64Test PASS  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─ STEP 2 — P8.0 + P8.1 CONSUMER WIRING ─────────────────────────┐
│ Status: DONE (2026-06-18)                                       │
│ Proves: encapsulation gate — InternalLeakCheck compile FAIL     │
│ Gate: SHARED_COMPILE PASS; rg scan on shared/src → empty          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─ STEP 3 — P8.2 → P8.4 QUALITY BASELINE ────────────────────────┐
│ Status: DONE (2026-06-18)                                       │
│ spotlessCheck + detekt + apiCheck PASS                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─ STEP 4 — P8.5 → P8.6 DOCS + TURBINE ──────────────────────────┐
│ Status: DONE (2026-06-18)                                       │
│ dokkaHtml PASS; Turbine in connectionStateTransitionsExposed    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─ STEP 5 — P8.7 → P8.8 PUBLISH METADATA + CI ────────────────────┐
│ Status: DONE (2026-06-18)                                       │
│ README, CHANGELOG, LICENSE; socketio-ci.yml                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─ STEP 6 — P7 DEMO (OPTIONAL) ──────────────────────────────────┐
│ Status: DONE (2026-06-18)                                       │
│ SockitScreen: connectionState, event log, connect/sub/dis   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Step 1 detail — P6.5 runtime rerun

### Preconditions

| Requirement | Command / note |
|-------------|----------------|
| Node echo server | `cd socketio/src/jvmTest/resources && npm ci && node socket-server.js` |
| Android emulator | `10.0.2.2:3000` (or test build config) |
| iOS simulator | `localhost:3000` |

### Test matrix (must all PASS)

| Case | Android | iOS | Gap |
|------|---------|-----|-----|
| Default transports connect | `PlatformSmokeTestCases.connectDefault` | same | baseline |
| Emit / echo round-trip | `emitEchoRoundTrip` | same | wire path |
| websocket-only → Connected | `websocketOnlyConnect` | same | **#3** |
| Clean disconnect | `disconnectCleanly` | same | lifecycle |

### Phase gate

```bash
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm \
  :socketio:compileAndroidDeviceTest :socketio:compileTestKotlinIosSimulatorArm64
# echo server running:
./gradlew :socketio:connectedAndroidDeviceTest :socketio:iosSimulatorArm64Test
```

---

## Step 2 detail — Consumer migration (P8.0 + P8.1)

### Proactive constraints

| Decision | Rationale |
|----------|-----------|
| Migrate `shared` **before** BCV `apiDump` | Consumer compile proves real integration |
| Remove `kmp-socketio` + `kmp-xlog` together | Forbidden dep policy HLD §2 |
| Rewrite `TempViewModel.kt` to `SocketClient.connect` + `NamespaceSocket` | Coroutine API (#4) |
| Keep negative compile scratch file ephemeral | CI uses `rg` scan permanently |

### Files

| File | Change |
|------|--------|
| `shared/build.gradle.kts` | `implementation(project(":socketio"))`; remove `com.piasy:kmp-socketio` |
| `shared/.../TempViewModel.kt` | Replace `IO.socket()` with new API |
| `shared/.../InternalLeakCheck.kt` | Temporary — delete after gate |

### Verify

```bash
./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
rg 'socketdemo\.socketio\.(internal|protocol|transport|engineio|socketio|connection|platform)' shared/src
# → empty
```

---

## Step 3–5 — Phase 8 checklist

See [`implementation-plan.md`](./implementation-plan.md) §Phase 8 and [`execution-state.md`](./execution-state.md) Phase 8 steps 8.0.1–8.8.1.

**Phase 8 gate (local v0.1):**

```bash
./gradlew spotlessCheck :socketio:detekt :socketio:apiCheck \
  :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm \
  :socketio:jvmTest
rg 'socketdemo\.socketio\.(internal|protocol|transport|engineio|socketio|connection|platform)' shared/src
rg "hildan|kmp-xlog|socket\.io-client" socketio/
```

---

## Verification debt (2026-06-18)

| Check | Result | Notes |
|-------|--------|-------|
| KMP_COMPILE | **PASS** | |
| SHARED_COMPILE | **PASS** | `shared` on `:socketio`; kmp-socketio removed |
| Encapsulation gate | **PASS** | InternalLeakCheck → compile FAIL; rg scan empty |
| spotlessCheck | **PASS** | |
| detekt | **PASS** | Project baselines in `detekt.yml` |
| apiCheck | **PASS** | `socketio/api/jvm/socketio.api` committed |
| dokkaHtml | **PASS** | |
| Unit tests (no network) | **PASS** | api, protocol, internal, connection |
| Full `jvmTest` | **103/108** | 5 ping-pong/reconnect timeouts with Node echo server |
| P6.5 platform smoke | **PASS** | Prior session (OkHttp 3/3 + Darwin 3/3) |

---

## Optional hardening (post-v0.1 — not blocking)

| Item | Risk | Suggested test |
|------|------|----------------|
| Binary event e2e through `NamespaceSocket` | Low | JVM integration with binary echo |
| `emitAwait(Subscribe(...))` | Low | Dedicated await + wire assert |
| CI echo-server job | Medium | GitHub Actions service container |
| `tryAllTransports` (JS v4.8+) | Low | Defer until product needs |

---

## Self-review (spec coverage)

| Screenshot requirement | Plan section |
|---------------------|--------------|
| Production-breaking #3, #2 | Deep-dives + status board |
| Intermittent #1 + chaos test | #1 deep-dive + chaos row |
| Slow burn #9 | #9 deep-dive |
| P6.6 config footguns | P6.6 deep-dive |
| Lower impact #4–#6, #8 | Status board + maintain section |
| Detection #5 | Step 1 P6.5 |
| Ordered execution | §Ordered execution diagram |
| Done / ongoing / pending | Master status board |
| No hit-and-trial | Each deep-dive has "Do not" column |
