# Sockit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.adventures92/sockit)](https://central.sonatype.com/artifact/io.github.adventures92/sockit)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](../LICENSE)

Coroutine-first **Socket.IO v5 / Engine.IO v4** client for Kotlin Multiplatform — Android, iOS and
JVM, with the protocol implemented in-house and no third-party protocol or logging dependencies.

```kotlin
implementation("io.github.adventures92:sockit:0.0.2")
```

```kotlin
val client = SocketClient.connect("https://example.com")
val socket = client.namespace()

launch { socket.connectionState.collect(::render) }
launch { socket.events("price").collect(::handlePrice) }

socket.openAwait()
socket.emit("subscribe", "BTC-INR")

socket.close()
client.close()
```

## 📖 Documentation

**[adventures92.github.io/Sockit](https://adventures92.github.io/Sockit/)** — the guide: getting
started, connection state, reconnection, events, acknowledgements, configuration, and per-platform
notes.

**[/api/](https://adventures92.github.io/Sockit/api/)** — the generated API reference.

Both are published on every release. The guide is the source of truth for usage; this file is
deliberately short so the two cannot drift.

## At a glance

| | |
|---|---|
| Targets | Android (minSdk 24), iOS arm64 + simulator arm64, JVM 11+ |
| Transports | polling, websocket, automatic upgrade |
| Server | `socket.io` 4.x — verified against 4.5.4 – 4.8.1 on every pull request |
| Public API | `dev.adven.sockit.api` only; everything else is `internal` |
| Dependencies | `kotlinx-*` and Ktor |

Multiplexing with reference-counted engines, exponential-backoff reconnect, binary attachments,
acknowledgements in both directions, a sealed error model, and a single-serial-worker threading
design so the API is safe to call from any thread.

See [Limitations](https://adventures92.github.io/Sockit/limitations.html) for what is deliberately
out of scope.

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the workflow and the gates a change has to pass, and
[docs/architecture.md](../docs/architecture.md) for the design and the reasoning behind the
constraints.

## Acknowledgements

The behaviour model for Engine.IO upgrade sequencing, multiplex-registry lifetime, and reconnect
backoff was studied against [kmp-socketio](https://github.com/HackWebRTC/kmp-socketio) and the
official [socket.io-client](https://github.com/socketio/socket.io-client). Neither is a dependency
and no code is shared — the protocol codec, transports, engine and public API here are original.
Wire behaviour is verified against the
[socket.io-protocol](https://github.com/socketio/socket.io-protocol) test vectors and a live
`socket.io` server matrix.
