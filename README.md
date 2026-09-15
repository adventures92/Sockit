# Sockit

[![socketio-ci](https://github.com/adventures92/Sockit/actions/workflows/socketio-ci.yml/badge.svg)](https://github.com/adventures92/Sockit/actions/workflows/socketio-ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.adventures92/sockit)](https://central.sonatype.com/artifact/io.github.adventures92/sockit)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

A **coroutine-first Socket.IO client for Kotlin Multiplatform** — Android, iOS and JVM — with an
in-house Engine.IO v4 / Socket.IO v5 implementation and no third-party protocol or logging
dependencies.

```kotlin
implementation("io.github.adventures92:sockit:0.0.2")
```

```kotlin
val client = SocketClient.connect(
    "https://example.com",
    socketOptions {
        transports(Transports.WEBSOCKET)
        auth { put("token", jwt) }
    },
)
val socket = client.namespace()

launch { socket.connectionState.collect(::render) }
launch { socket.events("quote").collect(::handleQuote) }

socket.openAwait()
socket.emit(Subscribe(buildJsonObject { put("pair", "BTC-INR") }))
```

**📖 [Documentation](https://adventures92.github.io/Sockit/)** · **[API reference](https://adventures92.github.io/Sockit/api/)**

## Why

| | Sockit | Typical alternatives |
|---|---|---|
| Targets | Android + iOS + JVM, one codebase | JS `socket.io-client` wrappers, JVM-only clients |
| API | `StateFlow` / `Flow` / `suspend` | Callback `on` / `emit` |
| Protocol | In-house Engine.IO 4 / Socket.IO 5 codec | Third-party protocol stacks |
| Dependencies | `kotlinx-*` + Ktor only | Mixed logging / protocol stacks |
| Threading | Single serial worker — no upgrade/drain/close races | Varies |

Transports are polling and WebSocket with automatic upgrade, multiplexed per origin, with
exponential-backoff reconnect, binary attachments, server-initiated acks and a sealed error model.
Verified against the official `socket.io-protocol` test vectors and a live `socket.io` **4.5.4 –
4.8.1** server matrix on every pull request.

## Repository layout

| Module | Purpose |
|--------|---------|
| [`socketio/`](./socketio/) | **The library.** The only published artifact. |
| [`shared/`](./shared/) | Compose Multiplatform demo — also the consumer-encapsulation gate that proves `internal` types cannot leak. |
| [`androidApp/`](./androidApp/) | Android demo entry point. |
| [`iosApp/`](./iosApp/) | iOS demo entry point (Xcode). |
| [`docs/`](./docs/) | Design system of record — [architecture](./docs/architecture.md), [diagrams](./docs/architecture-diagrams.md), [conformance & maintenance](./docs/socketio-conformance-and-maintenance.md). |

## Building

Requires JDK 17+ and the Android SDK. Everything runs through the Gradle wrapper.

```bash
# Quality gates — run before considering any :socketio change done
./gradlew :socketio:apiCheck spotlessCheck :socketio:detekt

# Unit + protocol tests (no server needed)
./gradlew :socketio:jvmTest --tests "dev.adven.sockit.protocol.*"

# Full JVM suite — spawns the bundled Node echo server itself
cd socketio/src/jvmTest/resources && npm ci && cd -
./gradlew :socketio:jvmTest

# Platform smoke (simulator / device required)
./gradlew :socketio:iosSimulatorArm64Test
./gradlew :socketio:connectedAndroidDeviceTest

# API reference
./gradlew :socketio:dokkaGeneratePublicationHtml   # → socketio/build/dokka/html/
```

Formatting is `spotless` + ktlint (`./gradlew spotlessApply`); static analysis is `detekt` at
`maxIssues: 0`. Any change to the public `api/` package needs a matching
`./gradlew :socketio:apiDump`.

### Demo apps

```bash
./gradlew :androidApp:assembleDebug     # Android
# iOS: open iosApp/ in Xcode and run
```

## Debugging the library itself

Full wire/FSM logs are **compiled out** of release builds. For local library development:

```bash
./scripts/toggle-socketio-internal-logging.sh on   # or off / status / (no arg = flip)
./gradlew :androidApp:installDebug                 # rebuild — the flag is compile-time
```

The script writes `gradle/socketio-dev.local.properties` (gitignored); every Gradle build reads it
automatically. One-off override: `./gradlew -Psocketio.internalLogging=false …`. Logs land in
Logcat on Android, the Xcode console on iOS, and stdout under `jvmTest`.

Consumer-facing logging is separate and sanitized — `Logger.essential { … }` via
`socketOptions { logger = … }`, single tag `SocketIO`, no URLs, tokens or payloads. See
[logging](./socketio/README.md#logging).

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](./CONTRIBUTING.md) for the workflow and
the gates a change has to pass.

## License

[Apache 2.0](./LICENSE) — see [acknowledgements](./socketio/README.md#acknowledgements) for prior
art this client was studied against.
