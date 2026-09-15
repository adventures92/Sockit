# Sockit Socket.IO Library — Architecture Diagrams

> Derived from [`architecture.md`](./architecture.md), [`implementation-plan.md`](./implementation-plan.md), [`execution-state.md`](./execution-state.md).

---

## 1. Repository layout

```mermaid
flowchart TB
    subgraph Sockit["Sockit/"]
        socketio["socketio/ — KMP library (new)"]
        shared["shared/ — Demo Compose app"]
        androidApp["androidApp/"]
        iosApp["iosApp/"]
        docs["docs/ — HLD · LLD · Execution"]
    end

    subgraph socketio_src["socketio/src"]
        commonMain["commonMain (~95%)"]
        androidMain["androidMain — OkHttp"]
        iosMain["iosMain — Darwin"]
        commonTest["commonTest — protocol unit"]
        jvmTest["jvmTest — Node echo integration"]
        androidIT["androidInstrumentedTest — OkHttp smoke"]
        iosTest["iosSimulatorArm64Test — Darwin smoke"]
    end

    socketio --> socketio_src
    shared -.->|optional Phase 7| socketio
    androidApp --> shared
    iosApp --> shared
```

---

## 2. Layered architecture (bottom → top)

```mermaid
flowchart BT
    subgraph platform["platform/ — expect/actual"]
        P1["createPlatformHttpClient()"]
        P2["OkHttp (Android) · Darwin (iOS) · CIO (JVM test)"]
    end

    subgraph internal["internal/ + all non-api packages"]
        I1["WorkQueue · EventBus · SubscriptionHandle"]
        I2["StreamEventNames — subscribe/unsubscribe constants"]
        I3["protocol · transport · engineio · socketio — all internal"]
    end

    subgraph connection["connection/"]
        C1["ReconnectPolicy — backoff + jitter"]
    end

    subgraph protocol["protocol/ — pure Kotlin, no network"]
        PR1["Packets · EngineIoCodec · SocketIoCodec"]
        PR2["BinaryAssembler"]
    end

    subgraph transport["transport/"]
        T1["PollingTransport — HTTP long-poll"]
        T2["WebSocketTransport — Ktor WS"]
        T3["TransportFactory · HttpClientFactory"]
    end

    subgraph engineio["engineio/"]
        E1["EngineConnection — handshake, heartbeat, upgrade"]
        E2["UpgradeController — FSM"]
    end

    subgraph socketio_layer["socketio/"]
        S1["ConnectionManager · SocketClientRegistry"]
        S2["NamespaceSocketImpl"]
    end

    subgraph api["api/ — only public surface"]
        A1["SocketClient · NamespaceSocket"]
        A2["StreamCommand · Subscribe · Unsubscribe · SocketCommand"]
        A3["SocketOptions · Transports · ConnectionState · SocketError"]
        A4["Logger · SocketEvent · SocketPayload"]
    end

    subgraph apps["Consumers"]
        APP["Demo app / host apps"]
    end

    APP --> api
    api --> socketio_layer
    socketio_layer --> engineio
    engineio --> transport
    transport --> protocol
    transport --> platform
    engineio --> connection
    socketio_layer --> internal
    engineio --> internal
    transport --> internal
```

---

## 3. Threading model

```mermaid
flowchart LR
    subgraph callers["Any thread / coroutine"]
        UI["Compose UI"]
        VM["ViewModel"]
        BG["Background work"]
    end

    subgraph worker["WorkQueue — Dispatchers.Default.limitedParallelism(1)"]
        SM["State machine mutations"]
        BUF["writeBuffer · upgrade · close"]
    end

    subgraph public["Public API"]
        SF["StateFlow&lt;ConnectionState&gt;"]
        EF["Flow&lt;SocketError&gt;"]
        EV["events(name): Flow&lt;SocketEvent&gt;"]
    end

    UI & VM & BG -->|enqueue| worker
    worker -->|emit| SF & EF & EV
```

---

## 4. Runtime object graph (multiplexing)

```mermaid
flowchart TB
    subgraph registry["SocketClientRegistry (internal)"]
        KEY["cache key: scheme://host:port"]
        REF["ref count per ConnectionManager"]
    end

    subgraph client1["SocketClient (app-owned)"]
        NS1["NamespaceSocket /"]
        NS2["NamespaceSocket /futures"]
    end

    subgraph manager["ConnectionManager"]
        ENG["EngineConnection — one physical link"]
        RP["ReconnectPolicy"]
    end

    subgraph transports["Active transport"]
        POLL["PollingTransport"]
        WS["WebSocketTransport"]
    end

    client1 -->|namespace()| NS1 & NS2
    client1 -->|multiplex=true| registry
    registry -->|cache hit| manager
    NS1 & NS2 --> manager
    manager --> ENG
    ENG --> POLL
    ENG -.->|upgrade| WS
    ENG --> RP

    FORCE["forceNew=true"] -.->|bypass cache| manager
    DIFF["different URL"] -.->|new manager| manager
```

| Config | Physical engines | Notes |
|--------|------------------|-------|
| Same URL, `multiplex=true` | 1 | Namespaces share engine; reconnect affects all |
| Same URL, `forceNew=true` | N | Independent; not in global cache |
| Different URLs | N | Independent managers |

---

## 5. Connection lifecycle sequence

```mermaid
sequenceDiagram
    participant App
    participant NS as NamespaceSocket
    participant CM as ConnectionManager
    participant EC as EngineConnection
    participant PT as PollingTransport
    participant WS as WebSocketTransport
    participant Srv as Socket.IO Server

    App->>NS: open()
    NS->>CM: connect namespace
    CM->>EC: open()
    EC->>PT: GET /engine.io/?EIO=4&transport=polling
    PT->>Srv: handshake
    Srv-->>PT: Open {sid, pingInterval, pingTimeout, upgrades}
    PT-->>EC: onHandshake
    EC->>EC: start heartbeat (ping/pong)
    EC->>Srv: Socket.IO CONNECT (namespace + auth)
    Srv-->>EC: CONNECT ack
    EC-->>NS: Connected

    opt upgrade enabled
        EC->>WS: probe WebSocket (isProbe=true)
        WS->>Srv: WS upgrade + probe ping
        Srv-->>WS: probe pong
        EC->>PT: pause polling
        EC->>WS: upgrade packet
        EC->>EC: switch transport → WS
    end

    App->>NS: emit(Subscribe(...))
    NS->>EC: encode EVENT
    EC->>WS: send (or PT if no upgrade)
    WS->>Srv: wire frame

    Srv-->>WS: EVENT echo
    WS-->>EC: decode
    EC-->>NS: route to namespace
    NS-->>App: events("rateUpdate").collect

    Note over EC: pingTimeout or transport error
    EC->>EC: SocketError → errors Flow
    EC->>EC: schedule reconnect (ReconnectPolicy)
    EC-->>NS: Reconnecting → Connected
```

---

## 6. Upgrade FSM (gap #1 — drain race fix)

```mermaid
stateDiagram-v2
    [*] --> IDLE

    IDLE --> PROBING: probe WebSocket opened
    PROBING --> PAUSING_POLL: probe pong received
    PAUSING_POLL --> UPGRADING: polling paused AND upgrade drain ack
    UPGRADING --> DONE: transport switched to WS
    DONE --> [*]

    note right of PROBING
        Probe ping: drainedCount=0
        No writeBuffer decrement
    end note

    note right of UPGRADING
        Switch only when BOTH:
        • polling pause complete
        • upgrade drain acknowledged
    end note
```

**Defensive `onDrain`:** if `count > writeBuffer.size` → log + no-op (never throw).

---

## 7. Error propagation (gap #2, #6)

```mermaid
flowchart TD
    SEND["Transport.send() fails (async)"]
    MAP["Map → SocketError.SendFailed"]
    ERR["errors: Flow&lt;SocketError&gt;"]
    LOG["Logger.error"]
    ECLOSE["EngineConnection: close transport"]
    RECON["Schedule reconnect (if enabled)"]
    EMITFAIL["emitAwait / emitWithAck: throw only on pre-queue reject"]
    STATE["connectionState: Failed (terminal on connect errors)"]

    SEND --> MAP
    MAP --> ERR & LOG
    MAP --> ECLOSE --> RECON

    PARSE["Parse failure"] --> MAP2["SocketError.ParseError"] --> ERR
    PING["Ping timeout"] --> MAP3["SocketError.PingTimeout"] --> STATE
    TLS["TLS failure"] --> MAP4["SocketError.TlsFailure"] --> STATE
    CONN["CONNECT_ERROR packet"] --> MAP5["SocketError.ConnectError"] --> STATE
    RESERVED["Reserved event / validation"] --> EMITFAIL
```

| Surface | When |
|---------|------|
| `connectionState: StateFlow` | Lifecycle; `Failed` is terminal until `open()` again |
| `errors: Flow` | Send failures while connected, parse warnings, glitches before reconnect |
| `Logger.error` | Always — internal detail + throwable |
| `emitAwait` | Completes when the event is **queued** (WorkQueue → engine writeBuffer); does **not** await transport drain |
| Async transport `SendFailed` | `errors` only — does not fail a prior `emitAwait` that already returned |

---

## 8. Wire protocol stack

```mermaid
flowchart TB
    subgraph app_payload["App payloads"]
        STR["String"]
        JSON["JsonElement"]
        BIN["ByteString"]
    end

    subgraph sio["Socket.IO v5 (inside EIO message)"]
        S0["0 CONNECT"]
        S1["1 DISCONNECT"]
        S2["2 EVENT"]
        S3["3 ACK"]
        S4["4 CONNECT_ERROR"]
        S5["5 BINARY_EVENT"]
        S6["6 BINARY_ACK"]
    end

    subgraph eio["Engine.IO v4 (text)"]
        E0["0 Open"]
        E1["1 Close"]
        E2["2 Ping"]
        E3["3 Pong"]
        E4["4 Message → wraps SIO packet"]
        E5["5 Upgrade"]
        E6["6 noop"]
    end

    subgraph wire["Transport"]
        HTTP["Polling GET/POST"]
        WSF["WebSocket text/binary frames"]
    end

    app_payload --> SocketIoCodec
    SocketIoCodec["SocketIoCodec"] --> sio
    sio --> EngineIoCodec
    EngineIoCodec["EngineIoCodec"] --> eio
    eio --> HTTP & WSF
    BIN --> BinaryAssembler["BinaryAssembler"] --> sio
```

---

## 9. HttpClient & SocketOptions resolution

```mermaid
flowchart TD
    DSL["socketOptions { } DSL"]
    SNAP["Immutable SocketOptions"]
    SHARED{"httpClient provided?"}
    APP["App Ktor HttpClient\n(app owns lifecycle)"]
    PLATFORM["createPlatformHttpClient()"]
    OKHTTP["Android: OkHttp + WebSockets"]
    DARWIN["iOS: Darwin + WebSockets"]
    CIO["JVM test: CIO + WebSockets"]

    DSL --> SNAP --> SHARED
    SHARED -->|yes| APP
    SHARED -->|no| PLATFORM
    PLATFORM --> OKHTTP & DARWIN & CIO
    APP & OKHTTP & DARWIN & CIO --> FACTORY["HttpClientFactory → transports"]
```

---

## 10. Implementation phases (LLD pipeline)

```mermaid
flowchart LR
    P0["Phase 0\nScaffold\nGradle + module"]
    P1["Phase 1\nInfra & API types\nWorkQueue · errors"]
    P2["Phase 2\nProtocol codec\nEIO4 + SIO5"]
    P3["Phase 3\nPlatform HTTP\nexpect/actual"]
    P4["Phase 4\nTransports\nPolling + WS"]
    P5["Phase 5\nEngine\nUpgrade FSM"]
    P6["Phase 6\nSocket.IO layer\nJVM e2e"]
    P65["Phase 6.5\nPlatform smoke\nOkHttp + Darwin"]
    P7["Phase 7\nDemo app\noptional"]
    P8["Phase 8\nLibrary quality\nencapsulation · CI · publish"]

    P0 --> P1 --> P2 --> P3 --> P4 --> P5 --> P6 --> P65 --> P7 --> P8

    P0 -.->|gate: compile all targets| G0
    P1 -.->|gate: internal + connection tests| G1
    P2 -.->|gate: codec tests| G2
    P4 -.->|gate: KMP_COMPILE + transport tests| G4
    P5 -.->|gate: KMP_COMPILE + engine tests| G5
    P6 -.->|gate: KMP_COMPILE + jvmTest full| G6
    P65 -.->|gate: KMP_COMPILE + platform smoke| G65
    P7 -.->|gate: KMP_COMPILE + SHARED_COMPILE| G7
    P8 -.->|gate: quality + KMP_COMPILE + jvmTest| G8
```

---

## 11. Agent execution workflow

```mermaid
flowchart TD
  START(["Sub-agent session start"])
  READ["Read execution-state.md\n→ Current phase"]
  HLD["Cross-check HLD refs\nin architecture.md"]
  LLD["Implement phase tasks\nfrom implementation-plan.md"]
  STEP["Complete granular steps\nin order"]
  VERIFY["Run step verify commands"]
  GATE{"Phase gate\npasses?"}
  UPDATE["Update execution-state.md\ncheckboxes + Last verification"]
  STOP(["Stop — one phase per session"])
  NEXT["Advance Current phase\n(manual / next session)"]

  START --> READ --> HLD --> LLD --> STEP --> VERIFY --> GATE
  GATE -->|no| STEP
  GATE -->|yes| UPDATE --> STOP
  UPDATE -.->|human triggers| NEXT
```

**Doc chain:** `architecture.md` (HLD) → `implementation-plan.md` (LLD) → `execution-state.md` (execution).

---

## 12. Testing pyramid

```mermaid
flowchart TB
    subgraph commonTest["commonTest — no network"]
        U1["EngineIoCodec golden strings"]
        U2["SocketIoCodec encode/decode"]
        U3["BinaryAssembler round-trip"]
        U4["WorkQueue · ReconnectPolicy"]
        U5["UpgradeController FSM"]
    end

    subgraph jvmTest["jvmTest — Node echo server (CIO)"]
        I1["PollingTransport · WebSocketTransport"]
        I2["EngineConnection handshake + upgrade"]
        I3["ConnectionIntegrationTest matrix"]
        I4["close → reopen chaos (gap #1)"]
        I5["SendFailed propagation"]
    end

    subgraph platform["Platform runtime — mandatory v0.1"]
        A1["androidInstrumentedTest\nOkHttp + ws-only"]
        I6["iosSimulatorArm64Test\nDarwin + ws-only"]
    end

    subgraph ci["CI compile gate (KMP_COMPILE)"]
        C1["compileAndroidMain"]
        C2["compileKotlinIosSimulatorArm64"]
        C3["compileKotlinJvm"]
    end

    commonTest --> jvmTest --> platform
    jvmTest --> ci
    platform --> ci
```

---

## 13. Public API usage (app perspective)

```mermaid
flowchart LR
    subgraph connect["Setup"]
        C0["socketOptions { auth; Transports.*; ... }"]
        C1["SocketClient.connect(url, options)"]
        C2["client.namespace('/futures')"]
        C3["socket.open()"]
    end

    subgraph observe["Observe"]
        O0["isConnected() snapshot"]
        O1["connectionState.collect"]
        O2["errors.collect"]
        O3["events('rateUpdate').collect"]
    end

    subgraph act["Act"]
        A0["emit(Subscribe/Unsubscribe/SocketCommand)"]
        A1["emit('subscribe', payload)"]
        A2["emitAwait(...)"]
        A3["emitWithAck(...)"]
    end

    subgraph teardown["Teardown"]
        T1["socket.close()"]
        T2["client.close()"]
    end

    C0 --> C1 --> C2 --> C3 --> observe & act
    observe & act --> teardown
```

---

---

## 14. API encapsulation boundary

```mermaid
flowchart LR
    subgraph app["Consumer app"]
        IMP["May implement StreamCommand"]
        USE["SocketClient · NamespaceSocket · api types only"]
    end

    subgraph api["api/ — public"]
        PUB["StreamCommand · Subscribe · Unsubscribe · SocketCommand · Transports · SocketOptions · …"]
    end

    subgraph hidden["internal — not importable"]
        CONST["StreamEventNames"]
        IMPL["NamespaceSocketImpl · codecs · transports"]
    end

    USE --> PUB
    IMP --> PUB
    PUB --> IMPL
    Subscribe --> CONST
    Unsubscribe --> CONST
    IMPL -.->|blocked| app
    CONST -.->|blocked| app
```

---

## 16. Library quality tooling (Phase 8)

```mermaid
flowchart TB
    subgraph runtime[":socketio runtime — locked"]
        R1["kotlinx-coroutines · serialization · io"]
        R2["Ktor client"]
        R3["Injectable Logger via SocketOptions"]
    end

    subgraph build["Repo build / CI — not shipped"]
        B1["explicitApi()"]
        B2["Spotless + ktlint"]
        B3["detekt"]
        B4["Binary Compatibility Validator"]
        B5["Dokka"]
        B6["Turbine — test only"]
    end

    subgraph gate["Consumer encapsulation — Phase 8.1"]
        G1["Negative compile: internal import fails"]
        G2["rg scan: no internal package refs in shared/"]
    end

    subgraph host["Host app"]
        H1["Koin wires HttpClient + Logger"]
        H2["socketOptions { httpClient; logger }"]
    end

    host --> runtime
    gate --> runtime
    build --> runtime
```

**Rule:** detekt, ktlint, Dokka, BCV, Turbine, Koin — never in `:socketio` `implementation()` dependencies.

---

## 17. Gap prevention map (kmp-socketio)

```mermaid
mindmap
  root((Sockit v0.1))
    Threading
      WorkQueue single worker
      No JDK21 removeFirst
    Upgrade
      UpgradeController FSM
      Defensive onDrain
      Chaos jvmTest
    Errors
      Sealed SocketError
      errors Flow
      SendFailed never swallowed
    API
      Coroutine-first only
      StateFlow connectionState
      isConnected isDisconnected snapshots
    Multiplex
      SocketClientRegistry
      ref count + evict
    Platform
      OkHttp trustAllCerts dev
      WS onOpen unconditional
      Phase 6.5 smoke tests
    Deps
      In-house protocol
      Injectable Logger
      Optional shared HttpClient
    Encapsulation
      explicitApi
      api package only public
      StreamEventNames internal
      Phase 8.1 consumer gate
    Quality
      Spotless ktlint detekt
      Binary Compatibility Validator
      Dokka CI
```
