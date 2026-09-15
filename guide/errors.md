# Errors

Failures arrive two ways, and both speak the same vocabulary.

| | |
|---|---|
| `errors: Flow<SocketError>` | Non-terminal problems, reported as they happen |
| `SocketException` | Thrown by the suspending calls, wrapping the same `SocketError` |

So one `when` handles a failure regardless of how it reached you.

## The flow

```kotlin
socket.errors.collect { error -> report(error) }
```

Hot, and does not terminate. Terminal failures also appear on
[`connectionState`](connection-state.md) as `Failed`.

| `SocketError` | Cause |
|---------------|-------|
| `Timeout(phase)` | An operation exceeded its budget. `phase` says which |
| `TlsFailure(cause)` | TLS handshake or certificate problem |
| `ParseError(raw)` | A frame could not be decoded. Non-terminal — the connection survives |
| `PingTimeout` | The server's heartbeat was missed |
| `TransportClosed(reason)` | The transport closed unexpectedly |
| `SendFailed(cause)` | A write failed after the event was queued |
| `ConnectError(message, data)` | The server rejected the namespace connect — usually auth |

## The exception

```kotlin
try {
    socket.openAwait()
    socket.emitAwait("important", payload)
} catch (e: SocketException) {
    when (e.error) {
        is SocketError.ConnectError -> refreshCredentials()
        is SocketError.Timeout -> retryLater()
        else -> report(e.error)
    }
}
```

`SocketException` is sealed with two subclasses — `ConnectionFailed` from `openAwait()`, and
`SendFailed` from `emitAwait()` and `emitWithAck()`. Catching `SocketException` itself is usually
enough; the split tells you *where* it failed, while `error` tells you *what* failed.

New failure modes appear as new `SocketError` values, never as new exception types, so an
exhaustive `catch` stays exhaustive.

## Which failures throw, and which do not

This is the distinction worth internalising:

**`emitAwait` completes when the event is queued**, not when it reaches the server. It throws only
on *pre-queue* rejection — a reserved event name, an unencodable argument. Once queued, a transport
write failure is reported on `errors` as `SendFailed` and does **not** fail the call that queued it.

**`emit` never throws for transport reasons at all.** It is fire-and-forget; failures surface on
`errors` and trigger reconnect when enabled.

So neither call is an acknowledgement that the server received anything. If you need that, use
[`emitWithAck`](acknowledgements.md) — the server's own reply is the only real confirmation.
