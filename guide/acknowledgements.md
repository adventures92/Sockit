# Acknowledgements

The only real confirmation that a message arrived. Both directions are supported.

## Asking the server to confirm

```kotlin
socket.emitWithAck("echo", "data").collect { ack ->
    // ack.args holds whatever the server passed to its callback
}
```

Returns a `Flow` that emits once and completes. It terminates rather than hanging if the namespace
disconnects first — the pending ack fails with `SocketError.TransportClosed`.

There is no timeout by default. Enable one, or a lost ack waits forever:

```kotlin
socketOptions { ackTimeoutMs = 5_000 }
```

On expiry the flow fails with `SocketError.Timeout`. The timer is cancelled when the ack arrives,
so a fast reply cannot produce a late spurious timeout.

## Replying when the server asks

A server emitting with a callback produces an event whose `ack` is non-null:

```kotlin
socket.events("whoami").collect { event ->
    event.ack?.send(userId)
}
```

`ack` is `null` for ordinary events, so its presence *is* the signal that a reply is expected.
Send at most once — subsequent calls on the same responder are ignored.

If the namespace disconnects before `send()` runs, the reply is dropped and reported on
[`errors`](errors.md) as `SendFailed` rather than failing silently.

Not replying is not an error on your side, but the server's callback will hang until its own
timeout. If you collect an event that carries an `ack`, reply on every path — including the ones
where you decided to ignore the event.

## Ordering

Acks are matched by id, not by arrival order, so concurrent `emitWithAck` calls cannot be
mismatched. Replies may arrive in any order.
