# Limitations

Stated plainly. These are current facts, not a roadmap.

## Not implemented

**`webtransport` is a stub.** `Transports.WEBTRANSPORT` and `experimentalWebTransport` register a
placeholder factory that does not carry traffic. Use polling and websocket.

**Connection state recovery is server-side only.** A server with `connectionStateRecovery`
(Socket.IO 4.6+) restores rooms on its side, but this client does not resume a session — it
reconnects as a new one. Re-emit your subscriptions; see [Reconnection](reconnection.md).

**A binary server event carrying an ack id is not supported.** Binary events work, and acks work,
but the combination — a `BINARY_EVENT` that also requests an acknowledgement — is not decoded. It
is rare in practice.

**Volatile emits are not implemented.** There is no "drop if not connected" variant; use
`isConnected()` if you need that behaviour.

## Deliberately out of scope

| | Why |
|---|---|
| Callback `on` / `emit` API | The coroutine API is the API. Two surfaces means two sets of semantics |
| Token refresh, credential storage | Needs your auth server and retry policy — see [Authentication](authentication.md) |
| Certificate pinning helpers | Pin rotation belongs to your release process — see [TLS](tls.md) |
| Lifecycle or connectivity observers | Your app knows what "backgrounded" means for it |
| Third-party logging backends | `Logger` is an interface; no dependency to reconcile |
| Server-side Socket.IO | This is a client |

## Platforms

Android, iOS (arm64 + simulator arm64) and JVM. **No** Desktop-native, JS, Wasm, watchOS, tvOS or
macOS targets, and **no `iosX64`** — Intel Mac simulators are not covered.

Adding a target is additive and safe. Removing a published one breaks every consumer, so the list
is a commitment rather than a default.

## Heartbeat

Engine.IO v4 has the server ping and the client pong. This client also pings proactively on an
interval — a v3-era behaviour layered on top of correct v4 pong handling. It interoperates
correctly but sends a little more traffic than strictly required.
