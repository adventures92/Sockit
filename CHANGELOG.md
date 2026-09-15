# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- The demo APK attached to a release now reports the library version it was built from.
  `sockit-demo-0.0.1-debug.apk` identified itself as `1.0` internally, because `versionCode`
  and `versionName` were hardcoded in the demo app rather than derived from the published
  version. The library artifacts themselves were unaffected.

## [0.0.1] - 2026-09-15

Initial public release.

### Added

- Kotlin Multiplatform Socket.IO client for **Android**, **iOS** (arm64 + simulator arm64) and
  **JVM**, published from a single Gradle module — `commonMain` resolves the right artifact per
  target automatically
- In-house **Engine.IO v4 / Socket.IO v5** protocol codec. No third-party Socket.IO, protocol or
  logging dependencies — only `kotlinx-*` and Ktor
- Coroutine-first public API: `SocketClient`, `NamespaceSocket`, `connectionState: StateFlow`,
  `events(name): Flow`, `errors: Flow`, and `suspend` variants (`openAwait`, `emitAwait`,
  `emitWithAck`, `closeAwait`)
- **Polling and WebSocket** transports with automatic upgrade, and a `websocket`-only mode
- **Multiplexing** — namespaces on one origin share a single engine, reference-counted through an
  internal registry; `forceNew` opts out
- **Reconnection** with exponential backoff and jitter, plus `pauseReconnect()` / `resumeReconnect()`
  so the host app owns the policy
- **Binary attachments** in both directions as `kotlinx-io` `ByteString`
- **Acknowledgements** both ways — `emitWithAck` for client-initiated, and `SocketEvent.ack` for
  responding to server-initiated ones
- Typed, nested `auth` as a `JsonObject`, plus `extraHeaders` for polling and the WebSocket upgrade
- Sealed `SocketError` on the `errors` flow, and a public sealed `SocketException`
  (`ConnectionFailed` / `SendFailed`) carrying the same error from suspending calls
- `Subscribe` / `Unsubscribe` / `SocketCommand` typed outbound commands
- Injectable `Logger` (`Logger.NoOp` by default, `Logger.essential { }` sanitized — no URLs, tokens
  or payloads), and an optional shared Ktor `HttpClient`
- R8 consumer keep rules shipped in the AAR
- Single-serial-worker threading model: all connection state is mutated on one coroutine, so the
  public API is safe to call from any thread

### Compatibility

- Verified against the official
  [`socket.io-protocol`](https://github.com/socketio/socket.io-protocol) test vectors
- Smoke-tested against `socket.io` servers **4.5.4, 4.6.2 (with and without
  `connectionStateRecovery`), 4.7.5 and 4.8.1** on every pull request

### Known limitations

- `webtransport` is an experimental stub and is not functional
- Connection state recovery (Socket.IO 4.6+) is server-side only; the client does not resume
- A binary server event that also carries an ack id is not yet supported
- No `iosX64` target — Intel Mac simulators are not covered
- No token refresh, certificate pinning helpers, or callback-style `on` / `emit` API

[Unreleased]: https://github.com/adventures92/Sockit/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/adventures92/Sockit/releases/tag/v0.0.1
