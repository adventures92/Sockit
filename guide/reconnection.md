# Reconnection

On by default. When the engine drops unexpectedly:

1. The namespace moves to `Reconnecting(attempt)`.
2. The engine retries with exponential backoff plus jitter, from `reconnectionDelayMs` up to
   `reconnectionDelayMaxMs`.
3. On reopen, every namespace that was still *open* — you called `open()` and not `close()` —
   sends its `CONNECT` packet again automatically.
4. Your `events(name)` collectors stay subscribed throughout. There is nothing to re-register.
5. State returns to `Connected`.

The jitter is `randomizationFactor` and exists so a server restart does not bring every client
back simultaneously.

## The one thing you must do yourself

**Re-emit your server-side subscriptions.** The library reconnects the *connection*; it does not
replay your outbound messages. If your server tracks rooms or channel membership per connection,
that state is gone after a drop and only you know how to rebuild it.

```kotlin
socket.connectionState.collect { state ->
    if (state is ConnectionState.Connected) {
        socket.emit(Subscribe(buildJsonObject { put("pair", "BTC-INR") }))
    }
}
```

Because `Connected` is re-entered after every successful reconnect, this handles both the first
connect and every subsequent one. Replaying emits automatically was considered and rejected — the
library cannot know which messages are idempotent, and replaying a non-idempotent one is worse
than dropping it.

Servers with `connectionStateRecovery` enabled (Socket.IO 4.6+) restore rooms themselves. Sockit
does not implement the client half of that yet, so assume you need to re-subscribe.

## What to do per situation

| Situation | What to do |
|-----------|------------|
| Transient network drop | Nothing. Re-subscribe if your server needs it |
| `Failed` — auth rejected, ping timeout, TLS | Terminal. Fix the cause, then `close()` and connect with new options |
| `reconnectionAttempts` exhausted | Retries stop. Call `open()` to start again |
| Intentional shutdown | `close()` sends a force-close. No reconnect is scheduled |

## Tuning

```kotlin
socketOptions {
    reconnection = true
    reconnectionAttempts = Int.MAX_VALUE
    reconnectionDelayMs = 1_000
    reconnectionDelayMaxMs = 5_000
    randomizationFactor = 0.5
}
```

Defaults retry forever, which suits a foreground app that should recover on its own. Set
`reconnectionAttempts` to a finite number if you would rather surface a failure to the user, and
`reconnection = false` to manage retries entirely yourself.

To stop retrying without disconnecting — backgrounding, say — use
[`pauseReconnect()`](lifecycle.md#pausing-without-disconnecting) rather than reconfiguring.
