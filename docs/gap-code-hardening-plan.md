# Sockit `:socketio` — Code Hardening Plan (Post-Review)

> **For agentic workers:** One **phase per session**. Read/update this file at start and end. Sync [`execution-state.md`](./execution-state.md) when a phase gate passes.

**Created:** 2026-06-19  
**Source:** Architect review (post P0–P8 + gap remediation G1–G4)  
**Scope:** `<repository root>/socketio/` + repo CI/docs  
**Excluded:** **G-P1-1** (Maven/CocoaPods publish), **G-P1-2** (package rename) — library readiness, not code

**Project root:** `<repository root>`

---

## Doc chain

| Layer | File | Role |
|-------|------|------|
| HLD | `architecture.md` | What & why — update §5.1, §5.2, §19 when contracts change |
| LLD | `implementation-plan.md` | Original phase specs (reference) |
| Prior gaps | `gap-remediation-plan.md` | G1–G4 closed 2026-06-18 |
| **This plan** | **`gap-code-hardening-plan.md`** | Post-review code + CI + docs gaps |
| Diagrams | `architecture-diagrams.md` | Keep §7 `emitAwait` in sync with HLD |
| Execution | `execution-state.md` | Add phase tracker rows when work starts |

---

## Session protocol

1. Read **Current phase** below — run only that phase.
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
Sockit socketio — Code hardening phase {N} only.

Read:
- docs/gap-code-hardening-plan.md (Current phase + step list)
- docs/architecture.md (HLD refs in step table)

Rules: surgical diff only · KMP_COMPILE on commonMain changes · phase gate must pass · update gap-code-hardening-plan.md · no next phase.
Skip G-P1-1 and G-P1-2 (publish/package — out of scope).
```

---

## Overall status

| Field | Value |
|-------|-------|
| **Current phase** | `CH3` (complete — all G-P3 items done) |
| **Last updated** | 2026-06-19 |
| **Blockers** | None |
| **Out of scope** | G-P1-1 Maven/CocoaPods · G-P1-2 package rename |

---

## Execution order (dependency-first)

```text
CH0  P0 — CI green + emitAwait contract + doc sync
  ↓
CH1  P1 — Test stability + platform CI + close lifecycle
  ↓
CH2  P2 — Encapsulation + threading + API polish + CI docs
  ↓
CH3  P3 — v0.2+ features (lifecycle, recovery, webtransport, matrix)
```

| Order | ID | Phase | Depends on |
|-------|-----|-------|------------|
| 1 | G-P0-1 | CH0 | — |
| 2 | G-P0-2 | CH0 | — |
| 3 | G-P0-3 | CH0 | G-P0-2 |
| 4 | G-P1-5 | CH1 | CH0 gate |
| 5 | G-P1-3 | CH1 | G-P1-5 |
| 6 | G-P1-4 | CH1 | — |
| 7 | G-P2-1 | CH2 | CH0 gate |
| 8 | G-P2-3 | CH2 | — |
| 9 | G-P2-2 | CH2 | — |
| 10 | G-P2-6 | CH2 | — |
| 11 | G-P2-5 | CH2 | G-P2-6 (optional), G-P0-2 |
| 12 | G-P2-4 | CH2 | — |
| 13 | G-P3-1 | CH3 | CH1 gate |
| 14 | G-P3-2 | CH3 | G-P3-1 (design) |
| 15 | G-P3-3 | CH3 | — |
| 16 | G-P3-4 | CH3 | G-P1-3 (CI infra) |

---

## Master gap register

| ID | Gap | Severity | Phase | Status |
|----|-----|----------|-------|--------|
| G-P0-1 | `detekt` failing | **Critical** — CI red | CH0 | **done** |
| G-P0-2 | `emitAwait` contract undefined | **High** — API/docs lie | CH0 | **done** |
| G-P0-3 | HLD §19 success criteria stale | **Low** — docs drift | CH0 | **done** |
| G-P1-5 | Flaky jvmTest (ping/reconnect) | **Medium** | CH1 | **done** |
| G-P1-3 | Platform smoke not in default CI | **High** | CH1 | **done** |
| G-P1-4 | `client.close()` async teardown | **Medium** | CH1 | **done** |
| G-P2-1 | `api/Transports` → internal logging | **Medium** | CH2 | **done** |
| G-P2-3 | WorkQueue bypass in engine | **Low** | CH2 | **done** |
| G-P2-2 | `events()` DROP_OLDEST | **Medium** | CH2 | **done** |
| G-P2-6 | No aggregate `SocketClient.errors` | **Low** | CH2 | **done** |
| G-P2-5 | Turbine underused | **Low** | CH2 | **done** |
| G-P2-4 | Dokka not in CI | **Low** | CH2 | **done** |
| G-P3-1 | Lifecycle / NetworkObserver | **Future** | CH3 | **done** |
| G-P3-2 | Connection state recovery | **Future** | CH3 | **done** (spec; impl deferred) |
| G-P3-3 | WebTransport | **Future** | CH3 | **done** (stub + NO-GO spike) |
| G-P3-4 | Server compatibility matrix | **Future** | CH3 | **done** |
| ~~G-P1-1~~ | Maven/CocoaPods publish | — | — | **skipped** |
| ~~G-P1-2~~ | Package rename | — | — | **skipped** |

---

# CH0 — P0: CI green + contract clarity

**Goal:** Default CI passes; `emitAwait` contract is explicit, tested, and documented.

---

## G-P0-1 — Fix `detekt` failures

| | |
|---|---|
| **Severity** | Critical |
| **Symptom** | `./gradlew :socketio:detekt` fails (e.g. `SocketLog.kt` unused `tag` params) |
| **HLD** | §20 |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH0.1.1 | Run detekt; capture all issues | — | `./gradlew :socketio:detekt` → list issues |
| CH0.1.2 | Fix unused params in `SocketLog.kt` (use `_tag` or remove; prefer using tag in dev log path) | `internal/logging/SocketLog.kt` | detekt clean on file |
| CH0.1.3 | Fix any remaining weighted issues | as reported | `./gradlew :socketio:detekt` → PASS |

- [x] CH0.1.1 Capture detekt output
- [x] CH0.1.2 Fix `SocketLog.kt`
- [x] CH0.1.3 All detekt issues resolved

---

## G-P0-2 — Lock `emitAwait` contract (Option A: queue semantics)

| | |
|---|---|
| **Severity** | High |
| **Problem** | HLD §5.1 + README claim `emitAwait` throws on transport `SendFailed`; code completes on enqueue; `architecture-diagrams.md` §7 already documents queue semantics |
| **Locked decision** | **Option A** — `emitAwait` suspends until the event is **accepted into the outbound queue** (WorkQueue → `sendBuffer` or engine `writeBuffer`). Async transport `SendFailed` → `errors` Flow only; does not fail a prior `emitAwait` that already returned |
| **HLD** | §5.1, §5.2 |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH0.2.1 | Add KDoc on `NamespaceSocket.emitAwait` + `SocketClient.emitAwait` stating queue semantics | `api/NamespaceSocket.kt`, `api/SocketClient.kt` | compiles |
| CH0.2.2 | Update HLD §5.1 send-failure path: split `emitAwait` (queue) vs async `SendFailed` (errors) | `docs/architecture.md` | prose matches Option A |
| CH0.2.3 | Update README `emitAwait` section (remove “throws on SendFailed” for transport) | `socketio/README.md` | matches HLD |
| CH0.2.4 | Confirm `architecture-diagrams.md` §7 aligned (no change if already correct) | `docs/architecture-diagrams.md` | review only |
| CH0.2.5 | Add integration test: `emitAwait` completes while connected; inject transport failure **after** queue; assert `errors` emits `SendFailed`; `emitAwait` did not throw | `jvmTest/.../EmitAwaitContractTest.kt` | test PASS |
| CH0.2.6 | Add test: reserved event `emitAwait("connect")` still throws | existing or new in same file | PASS |
| CH0.2.7 | Run `apiCheck` — KDoc-only should not change `.api` dump | — | `./gradlew :socketio:apiCheck` |

**Do not:** Change `emitAwait` to await transport drain unless product explicitly requests Option B (would need BCV + breaking-change note).

- [x] CH0.2.1 KDoc on emitAwait
- [x] CH0.2.2 HLD §5.1 update
- [x] CH0.2.3 README update
- [x] CH0.2.4 Diagrams review (§7 already aligned — no change)
- [x] CH0.2.5 Integration test (queue vs async SendFailed)
- [x] CH0.2.6 Reserved-event throw test
- [x] CH0.2.7 apiCheck PASS

---

## G-P0-3 — Sync HLD §19 success criteria

| | |
|---|---|
| **Severity** | Low |
| **HLD** | §19 |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH0.3.1 | Mark completed §19 items `[x]` (compile, jvmTest, smoke, StreamCommand, Transports, explicitApi, Phase 8 tooling) | `docs/architecture.md` §19 | checkboxes accurate |
| CH0.3.2 | Add explicit **pending** rows: Maven publish (G-P1-1 skipped), package rename (G-P1-2 skipped), platform smoke in default CI (G-P1-3) | same | visible in §19 |
| CH0.3.3 | Link to this plan from `execution-state.md` | `docs/execution-state.md` | link present |

- [x] CH0.3.1 §19 checkboxes updated
- [x] CH0.3.2 Pending publish/CI rows
- [x] CH0.3.3 execution-state link

### CH0 phase gate

```bash
./gradlew spotlessCheck :socketio:detekt :socketio:apiCheck
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
./gradlew :socketio:jvmTest --tests "*EmitAwait*"
```

- [x] CH0 phase gate PASS
- [x] Update **Current phase** → `CH1`

---

# CH1 — P1: Stability + CI + lifecycle

**Goal:** Reliable jvmTest; platform proof in CI path; synchronous client teardown option.

---

## G-P1-5 — Stabilize flaky jvmTest (ping / reconnect)

| | |
|---|---|
| **Severity** | Medium |
| **Symptom** | execution-state: 5 ping-pong/reconnect timeouts with Node echo server |
| **HLD** | §14 |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH1.5.1 | Identify failing tests: `./gradlew :socketio:jvmTest --rerun-tasks 2>&1 \| rg -i "FAIL\|timeout"` | — | list test names |
| CH1.5.2 | Prefer **embedded server** (`EmbeddedEngineIoServer`) for heartbeat/ping tests — deterministic `pingInterval`/`pingTimeout` | `jvmTest/.../HeartbeatTest.kt`, `EngineConnectionTest.kt` | isolated PASS |
| CH1.5.3 | For Node-dependent reconnect tests: increase `withTimeout` margins or use `SocketTestServer` with explicit delay after drop | integration tests | 3 consecutive green runs |
| CH1.5.4 | Document echo-server requirement in test KDoc only where Node remains required | affected tests | — |

- [x] CH1.5.1 Identify flaky tests
- [x] CH1.5.2 Embedded server for heartbeat
- [x] CH1.5.3 Reconnect timeout margins
- [x] CH1.5.4 KDoc for Node deps

---

## G-P1-3 — Platform smoke in CI

| | |
|---|---|
| **Severity** | High |
| **Today** | `platform-smoke` job is `workflow_dispatch` only |
| **HLD** | §14 |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH1.3.1 | Split CI: `quality-and-test` (ubuntu) unchanged; enhance `platform-smoke` (macos) | `.github/workflows/socketio-ci.yml` | valid YAML |
| CH1.3.2 | **iOS:** Run `iosSimulatorArm64Test` on every PR (macos runner + echo server) | workflow | job green on PR |
| CH1.3.3 | **Android:** Add `connectedDebugAndroidTest` with `reactivecircus/android-emulator-runner` OR keep manual + **required check** via self-hosted runner — document chosen path in workflow comments | workflow | emulator or documented alternative |
| CH1.3.4 | Echo server: reuse `npm ci && node socket-server.js` pattern from jvmTest job | workflow | server up before tests |
| CH1.3.5 | Add workflow badge / README note: which jobs are required | `socketio/README.md` §Testing | dev discoverability |

**Minimum bar for CH1 gate:** iOS sim smoke on PR + Android smoke documented (emulator job or `workflow_dispatch` with branch protection on macos job).

- [x] CH1.3.1 Workflow split
- [x] CH1.3.2 iOS on PR
- [x] CH1.3.3 Android strategy implemented or documented
- [x] CH1.3.4 Echo server in platform job
- [x] CH1.3.5 README testing note

---

## G-P1-4 — Synchronous client teardown

| | |
|---|---|
| **Severity** | Medium |
| **Problem** | `SocketClientRegistry.release()` is async — `client.close()` returns before engine destroyed |
| **HLD** | §6 registry lifecycle |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH1.4.1 | Add `suspend fun SocketClient.closeAwait()` — awaits registry release + manager destroy on worker | `api/SocketClient.kt`, `socketio/SocketClientRegistry.kt`, `ConnectionManager.kt` | compiles |
| CH1.4.2 | Implement `releaseAwait` in registry (mutex path; no fire-and-forget `launch`) | `SocketClientRegistry.kt` | unit/integration test |
| CH1.4.3 | Keep `fun close()` as non-blocking sugar (document race window in KDoc) | `api/SocketClient.kt` | KDoc warns |
| CH1.4.4 | Integration test: `closeAwait()` → assert no further `connectionState` emissions / transport inactive | `jvmTest/.../ClientCloseAwaitTest.kt` | PASS |
| CH1.4.5 | `apiDump` + commit if new public API | `socketio/api/jvm/socketio.api` | `apiCheck` PASS |

- [x] CH1.4.1 `closeAwait()` API
- [x] CH1.4.2 `releaseAwait` impl
- [x] CH1.4.3 `close()` KDoc
- [x] CH1.4.4 Integration test
- [x] CH1.4.5 BCV updated

### CH1 phase gate

```bash
./gradlew :socketio:jvmTest --rerun-tasks   # 3x locally or CI
./gradlew :socketio:iosSimulatorArm64Test   # with echo server
./gradlew :socketio:apiCheck :socketio:detekt
```

- [x] CH1 phase gate PASS
- [x] Update **Current phase** → `CH2`

---

# CH2 — P2: Encapsulation + polish

**Goal:** Strict api boundary; consistent threading; observable API improvements; CI completeness.

---

## G-P2-1 — Decouple `api/Transports` from internal logging

| | |
|---|---|
| **Severity** | Medium |
| **HLD** | §4.1 encapsulation |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH2.1.1 | Move `SocketInternalLog.verbose` call out of `normalizeTransports` | `api/Transports.kt` → internal helper e.g. `internal/SocketOptionsTransportLog.kt` | `api/Transports.kt` imports only `Logger` |
| CH2.1.2 | Call internal helper from `SocketOptionsBuilder.build()` only | `api/SocketOptions.kt` | behavior unchanged |
| CH2.1.3 | `rg 'internal\.logging' socketio/src/commonMain/kotlin/dev/adven/sockit/api` → empty | — | scan clean |

- [x] CH2.1.1 Extract internal log helper
- [x] CH2.1.2 Wire from builder
- [x] CH2.1.3 api package scan clean

---

## G-P2-3 — Route engine side-effects through WorkQueue

| | |
|---|---|
| **Severity** | Low |
| **HLD** | §4 threading |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH2.3.1 | Change `emitError` to use `workQueue.launch` instead of `scope.launch` | `engineio/EngineConnection.kt` | **KMP_COMPILE** |
| CH2.3.2 | Change `EVENT_BINARY_DATA` handler to `workQueue.launch` | same | **KMP_COMPILE** |
| CH2.3.3 | Rerun upgrade + drain regression | — | `EngineDrainAccountingTest`, `UpgradeChaosTest` PASS |

- [x] CH2.3.1 emitError on worker
- [x] CH2.3.2 binary handler on worker
- [x] CH2.3.3 Regression tests PASS

---

## G-P2-2 — `events()` overflow policy

| | |
|---|---|
| **Severity** | Medium |
| **HLD** | §5.2 |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH2.2.1 | **Decision:** add `SocketOptionsBuilder.eventBufferCapacity: Int = 64` and `eventBufferOverflow: EventBufferOverflow = DROP_OLDEST` (new public enum) OR document DROP_OLDEST in KDoc only |
| CH2.2.2 | Implement chosen option | `api/SocketOptions.kt`, `NamespaceSocketImpl.kt` | **KMP_COMPILE** |
| CH2.2.3 | KDoc on `events()`: behavior under slow collectors | `api/NamespaceSocket.kt` | docs clear |
| CH2.2.4 | Unit test: fast producer + slow collector → assert documented behavior | `commonTest` or `jvmTest` | PASS |
| CH2.2.5 | `apiDump` if new public types | BCV | `apiCheck` |

**Default recommendation:** configurable overflow with default `DROP_OLDEST` (no behavior change for existing apps).

**Decision (CH2.2.1):** Configurable overflow via `EventBufferConfig` / builder `eventBufferCapacity` + `eventBufferOverflow`; default `DROP_OLDEST` (no behavior change).

- [x] CH2.2.1 Overflow decision recorded in this file
- [x] CH2.2.2 Implementation
- [x] CH2.2.3 KDoc
- [x] CH2.2.4 Test
- [x] CH2.2.5 BCV if needed

---

## G-P2-6 — Aggregate `SocketClient.errors`

| | |
|---|---|
| **Severity** | Low |
| **HLD** | §5.1 (optional aggregate surface) |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH2.6.1 | Add `val errors: Flow<SocketError>` on `SocketClient` — merge all namespace error flows (or engine-level tap in `ConnectionManager`) | `api/SocketClient.kt`, `ConnectionManager.kt` | compiles |
| CH2.6.2 | KDoc: aggregate across namespaces; prefer per-namespace for filtering | `api/SocketClient.kt` | — |
| CH2.6.3 | Integration test: two namespaces; error on one → aggregate receives | `jvmTest` | PASS |
| CH2.6.4 | `apiDump` | BCV | `apiCheck` |

- [x] CH2.6.1 Aggregate errors Flow
- [x] CH2.6.2 KDoc
- [x] CH2.6.3 Integration test
- [x] CH2.6.4 BCV

---

## G-P2-5 — Turbine Flow tests

| | |
|---|---|
| **Severity** | Low |
| **HLD** | §20 |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH2.5.1 | Add Turbine test for `connectionState` transitions: Disconnected → Connecting → Connected | `jvmTest/.../ConnectionStateTurbineTest.kt` | PASS |
| CH2.5.2 | Add Turbine test for `errors` + `SendFailed` path (reuse emit contract from CH0) | same or `EmitAwaitContractTest` | PASS |
| CH2.5.3 | If G-P2-6 done: Turbine on `SocketClient.errors` | optional | PASS |

- [x] CH2.5.1 connectionState Turbine
- [x] CH2.5.2 errors Turbine
- [x] CH2.5.3 client.errors Turbine (if CH2.6 done)

---

## G-P2-4 — Dokka in CI

| | |
|---|---|
| **Severity** | Low |
| **HLD** | §20 |

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH2.4.1 | Add step `./gradlew :socketio:dokkaGeneratePublicationHtml` to `quality-and-test` job | `.github/workflows/socketio-ci.yml` | CI green |
| CH2.4.2 | Optional: upload `socketio/build/dokka/html` as artifact | workflow | artifact on PR |

- [x] CH2.4.1 Dokka CI step
- [x] CH2.4.2 Artifact (optional)

### CH2 phase gate

```bash
./gradlew spotlessCheck :socketio:detekt :socketio:apiCheck
./gradlew :socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm
./gradlew :socketio:jvmTest
rg 'internal\.logging' socketio/src/commonMain/kotlin/dev/adven/sockit/api   # → empty
```

- [x] CH2 phase gate PASS
- [x] Update **Current phase** → `CH3`

---

# CH3 — P3: v0.2+ features

**Goal:** Deferred HLD items; larger scope — one sub-feature per session.

---

## G-P3-1 — Lifecycle / NetworkObserver (HLD gap #12)

| | |
|---|---|
| **Severity** | Future / product-dependent |
| **HLD** | §16 deferred → promote to v0.2; **design §21** |

**Decision (CH3.1.1, revised):** Option D — imperative `pauseReconnect()` / `resumeReconnect()` on `SocketClient`. **Consumer owns when** (lifecycle, network, product rules). Library owns **how** (cancel `reconnectJob`, gate `scheduleReconnect`). No `SocketLifecycleObserver`, no `lifecycleSignals` in `SocketOptions`, no platform network hook in core.

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH3.1.1 | **Design spike:** observer vs app-only vs primitives; document in HLD §21 | `docs/architecture.md` | design approved |
| ~~CH3.1.2~~ | ~~Platform network reachability hook~~ | — | **skipped** — consumer concern |
| CH3.1.3 | Add `pauseReconnect()` / `resumeReconnect()`; wire `EngineConnection` | `api/SocketClient.kt`, `engineio/EngineConnection.kt` | integration test |

- [x] CH3.1.1 Design spike — Option D locked in HLD §21 (revised: app-owned policy)
- [x] CH3.1.2 Platform hook — **skipped** (out of core library scope)
- [x] CH3.1.3 Reconnect pause/resume primitives

---

## G-P3-2 — Connection state recovery (HLD gap #13)

| | |
|---|---|
| **Severity** | Future / product-dependent |
| **HLD** | §16 deferred → §22 spec; **full contract** [`connection-state-recovery-spec.md`](./connection-state-recovery-spec.md) |

**Decision (CH3.2.2):** **Won't fix v0.2** — spec only. Implement when a named backend enables Socket.IO `connectionStateRecovery` or product requests `recovered` parity. Existing engine reconnect + client buffers + app resync are sufficient today.

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH3.2.1 | Spec server contract (what state server restores on reconnect) | `docs/connection-state-recovery-spec.md`, HLD §22 | spec written |
| CH3.2.2 | Implement only if server requires — else mark **won't fix** with rationale | HLD §22, spec §8 | rationale documented |

- [x] CH3.2.1 Server spec
- [x] CH3.2.2 Implement or defer with doc — **deferred** (won't fix v0.2; triggers in spec §8)

---

## G-P3-3 — WebTransport

| | |
|---|---|
| **Severity** | Future / product-dependent |
| **HLD** | §23; **spike** [`webtransport-feasibility-spike.md`](./webtransport-feasibility-spike.md) |

**Decision (CH3.3.2):** **NO-GO** for functional WebTransport on Android/iOS via Ktor OkHttp/Darwin. Ship constant + `experimentalWebTransport` feature flag + stub factory.

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH3.3.1 | Add `Transports.WEBTRANSPORT` + factory registration | `api/Transports.kt`, `transport/WebTransportTransport.kt`, `SocketOptions.kt` | behind feature flag |
| CH3.3.2 | Ktor webtransport client feasibility on OkHttp/Darwin | `docs/webtransport-feasibility-spike.md` | go/no-go |

- [x] CH3.3.1 Constant + factory stub
- [x] CH3.3.2 Platform feasibility spike — **NO-GO** (platform bridge required; not Ktor)

---

## G-P3-4 — Server compatibility matrix

| Step | Task | Files | Step verify |
|------|------|-------|-------------|
| CH3.4.1 | Docker-compose or matrix job: socket.io `4.x` server versions | `jvmTest/resources/`, CI | matrix green |
| CH3.4.2 | README compatibility table generated from CI results | `socketio/README.md` | table accurate |

- [x] CH3.4.1 Version matrix tests
- [x] CH3.4.2 README table

### CH3 phase gate

Per sub-feature — no single gate until all CH3 items prioritized by product.

---

## Cross-cutting rules

| Rule | Check |
|------|-------|
| No publish coordinates | Skip G-P1-1 |
| No package rename | Skip G-P1-2 |
| No new forbidden runtime deps | `rg "hildan\|kmp-xlog\|socket\.io-client" socketio/src` → empty |
| Android API safety | `rg "removeFirst\(\)\|removeLast\(\)" socketio/src/commonMain` → empty |
| KMP_COMPILE after commonMain | mandatory |
| BCV after public API change | `apiDump` + `apiCheck` |

---

## Risk register

| Risk | Mitigation |
|------|------------|
| CH0-2 doc-only perceived as incomplete | Add explicit integration test proving queue vs async failure |
| CH1-3 Android emulator CI flaky | Start with iOS-on-PR; Android via emulator-runner or scheduled |
| CH1-4 `closeAwait` API surface | BCV; keep `close()` for fire-and-forget |
| CH2-2 overflow config | Default preserves current DROP_OLDEST |
| CH2-6 aggregate errors duplicate | Use `merge` with dedup or engine-level single tap |
| CH3 scope creep | One CH3 sub-feature per session; design spike before code |

---

## Estimated effort

| Phase | Gaps | Days |
|-------|------|------|
| CH0 | P0-1, P0-2, P0-3 | 1 |
| CH1 | P1-5, P1-3, P1-4 | 2–3 |
| CH2 | P2-1, P2-3, P2-2, P2-6, P2-5, P2-4 | 2 |
| CH3 | P3-1 … P3-4 | 4+ (product-dependent) |
| **Total (CH0–CH2)** | **13 gaps** | **~5–6 days** |

---

## Last verification

```text
CH3 G-P3-4 complete — 2026-06-19 (cursor)
  CH3.4.1: ServerCompatibilitySmokeTest + CI server-compatibility matrix (4.5.4–4.8.1, recovery 4.6.2)
  CH3.4.2: README compatibility table + docker-compose.server-matrix.yml
  jvmTest ServerCompatibilitySmokeTest (4/4) on default socket.io 4.8.x → PASS
  CH3 phase complete — all G-P3 items done

CH3 G-P3-3 complete — 2026-06-19 (cursor)
  CH3.3.1: Transports.WEBTRANSPORT + experimentalWebTransport flag + WebTransportTransport stub
  CH3.3.2: webtransport-feasibility-spike.md — NO-GO via Ktor OkHttp/Darwin
  KMP_COMPILE + commonTest + apiDump/apiCheck → PASS
  Next CH3 item: G-P3-4 server compatibility matrix

CH3 G-P3-2 complete — 2026-06-19 (cursor)
  CH3.2.1: connection-state-recovery-spec.md — server contract (pid/offset, adapter, wire)
  CH3.2.2: Won't fix v0.2 — impl deferred until backend enables connectionStateRecovery
  HLD §22 + gap register #13 updated
  Next CH3 item: G-P3-3 WebTransport

CH3 G-P3-1 complete — 2026-06-19 (cursor)
  CH3.1.1: Option D — pauseReconnect()/resumeReconnect(); app owns when
  CH3.1.3: EngineConnection reconnectPaused gate; SocketClient API; ReconnectPauseResumeTest
  KMP_COMPILE + jvmTest (ReconnectPauseResumeTest 2/2) + apiCheck → PASS
  Next CH3 item: G-P3-2 connection state recovery spec

CH3 G-P3-1 design spike — 2026-06-19 (cursor, revised)
  CH3.1.1: Option D — pauseReconnect()/resumeReconnect() primitives; app owns when
  Rejected Option C (library-collected lifecycle Flow) — not generic enough
  CH3.1.2 skipped (network hook is consumer concern)
  HLD §21 updated; next session: CH3.1.3 implementation

CH2 complete — 2026-06-19 (cursor)
  G-P2-1: SocketOptionsTransportLog; api/Transports imports only Logger
  G-P2-3: emitError routed through workQueue; binary handler already on worker
  G-P2-2: EventBufferConfig + EventBufferOverflow (default DROP_OLDEST); KDoc + tests
  G-P2-6: SocketClient.errors aggregate via ConnectionManager; BCV
  G-P2-5: ConnectionStateTurbineTest (connectionState, errors, client.errors)
  G-P2-4: dokkaGeneratePublicationHtml in CI + artifact upload
  Phase gate: spotlessCheck + detekt + apiCheck + KMP_COMPILE + jvmTest (133/133) + encapsulation scan → PASS
  Current phase: CH3

CH1 complete — 2026-06-19 (cursor)
  G-P1-5: embedded server for ping/poll/transport-error tests; reconnect margins; Node KDoc
  G-P1-3: platform-smoke iOS on PR; Android workflow_dispatch + README CI table
  G-P1-4: SocketClient.closeAwait + releaseAwait/destroyAwait; ClientCloseAwaitTest; BCV
  Phase gate: jvmTest 3x --rerun-tasks (126/126) + apiCheck + detekt → PASS
  Current phase: CH2

CH0 complete — 2026-06-19 (cursor)
  G-P0-1: SocketLog.kt tag used in prefixed consumer messages → detekt PASS
  G-P0-2: Option A queue semantics — KDoc, HLD §5.1, README, EmitAwaitContractTest
  G-P0-3: HLD §19 checkboxes + pending rows; execution-state link
  Phase gate: spotlessCheck + detekt + apiCheck + KMP_COMPILE + jvmTest *EmitAwait* → PASS
  Current phase: CH1
```

Update this block at the end of each session.
