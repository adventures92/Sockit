# Server compatibility

The wire protocol is **Engine.IO v4** and **Socket.IO packet v5** — the same pair the `socket.io`
npm **4.x** line speaks. `EIO=4` is sent on every request.

| `socket.io` server | Recovery opt-in | Verified by |
|--------------------|-----------------|-------------|
| **4.5.4** | — | CI matrix |
| **4.6.2** | off | CI matrix |
| **4.6.2** | on (`connectionStateRecovery`) | CI matrix |
| **4.7.5** | — | CI matrix |
| **4.8.1** | — | CI matrix + default test suite |

Every one of those runs on **every pull request**, not just before a release. Each cell exercises
a polling handshake, namespace connect, an echo round trip, an ack round trip, and a
websocket-only connection.

## Why this stays stable

"A new Socket.IO release" almost never means the *protocol* changed. It means new server software.
The protocol revisions have been frozen since 2020, and as long as a server answers `EIO=4` with
Socket.IO v5 framing, a conformant client needs no changes.

So the risk worth guarding against is not future drift — it is the gap between what a client does
today and what the spec already requires. That is covered by `ProtocolConformanceTest`, which
codifies vectors taken verbatim from the upstream
[`socket.io-protocol`](https://github.com/socketio/socket.io-protocol) test suite and runs on both
JVM and iOS. A protocol change would fail those first, pointing at exactly what moved.

## Other server implementations

Anything speaking Engine.IO v4 and Socket.IO v5 should work, but only the `socket.io` reference
server is tested. If you use another implementation and hit a difference, a bug report with the
raw frames is genuinely useful — see [Logging](logging.md#full-wire-logging).

## Running the matrix yourself

```bash
cd socketio/src/jvmTest/resources
docker compose -f docker-compose.server-matrix.yml up -d   # ports 3001–3004
```

The default test suite does not need Docker; it starts a Node echo server on an ephemeral port by
itself.
