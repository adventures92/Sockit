# Connection state

```kotlin
socket.connectionState.collect { state -> render(state) }
```

`connectionState` is a `StateFlow<ConnectionState>`, so it always has a current value and replays
it to a new collector immediately.

| State | Meaning |
|-------|---------|
| `Disconnected` | Not connected, and not trying |
| `Connecting` | Engine handshake or namespace connect in progress |
| `Connected` | The namespace is live and can carry events |
| `Reconnecting(attempt)` | The engine dropped; a retry is scheduled. `attempt` counts from 1 |
| `Failed(error)` | Terminal for this namespace until you `open()` again |

`SocketClient` exposes the same flow aggregated across its namespaces, which is what you usually
want for a connection indicator.

## Snapshot helpers

For a one-off check without collecting:

```kotlin
if (socket.isConnected()) socket.emit("ping")
```

`isDisconnected()` is true **only** for `Disconnected`. It is not the inverse of `isConnected()` —
`Connecting`, `Reconnecting` and `Failed` are all neither. When you mean "not usable right now",
test `isConnected()` and negate it.

## `open()` vs `openAwait()`

| | Behaviour |
|---|---|
| `open()` | Returns immediately. Moves to `Connecting`. React via `connectionState` |
| `openAwait()` | Suspends until `Connected`, or throws `SocketException.ConnectionFailed` |

Use `openAwait()` when the next line depends on a live namespace — a first emit, a subscription.
Use `open()` when you are driving UI from state anyway and have nothing to block on.

Both are idempotent on an already-connected namespace.

## `Failed` is terminal

Nothing recovers a `Failed` namespace automatically. That is deliberate: the usual causes are a
rejected token or an exhausted retry budget, and silently retrying either produces a loop.

```kotlin
socket.connectionState.collect { state ->
    when (state) {
        is ConnectionState.Connected -> resubscribe()
        is ConnectionState.Failed -> when (state.error) {
            is SocketError.ConnectError -> refreshTokenAndReconnect()
            else -> showRetryButton()
        }
        else -> Unit
    }
}
```

To recover, build a new `SocketOptions` — with a fresh token if that was the cause — and connect
again. See [Errors](errors.md) for what each `SocketError` means.
