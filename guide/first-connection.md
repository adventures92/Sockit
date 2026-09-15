# Your first connection

`SocketClient.connect` is a `suspend` function, and the flows it exposes are collected from a
coroutine scope. Everything below assumes you are inside one.

```kotlin
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

suspend fun run() = coroutineScope {
    val client = SocketClient.connect(
        "https://example.com",
        socketOptions {
            transports(Transports.WEBSOCKET)
            auth { put("token", jwt) }
        },
    )
    val socket = client.namespace()          // the default "/" namespace

    launch { socket.connectionState.collect(::render) }
    launch { socket.errors.collect(::report) }
    launch {
        socket.events("price").collect { event ->
            // event.name, event.args
        }
    }

    socket.openAwait()                       // suspends until Connected, or throws
    socket.emit("subscribe", "BTC-INR")

    socket.close()
    client.close()
}
```

## Reading that in order

**`connect` does not open the connection.** It builds the client and resolves the multiplex
registry. Nothing reaches the network until `open()` or `openAwait()`.

**Collect before you open.** `events(name)` is a cold `Flow` backed by a hot buffer, so events
that arrive before you subscribe are buffered rather than dropped. Collecting first is still the
habit worth keeping — it means you cannot miss the `Connecting → Connected` transition either.

**`openAwait()` suspends until the namespace is live**, and throws
[`SocketException.ConnectionFailed`](errors.md) if it fails. Use it when the next line depends on
being connected. Use [`open()`](connection-state.md#open-vs-openawait) when you would rather react
to `connectionState` than wait.

**`emit` is fire-and-forget.** It does not suspend and does not throw on a transport failure —
that surfaces on [`errors`](errors.md). See [Emitting](emitting.md) for when to use `emitAwait`
instead.

## Both closes are needed

```kotlin
socket.close()   // leaves the namespace
client.close()   // releases the engine
```

Cancelling the surrounding scope stops your collectors. It does **not** close the transport — the
engine and its reconnect policy belong to the client, not to your scope. A leaked `SocketClient`
keeps a socket open and keeps retrying. [Lifecycle](lifecycle.md) covers this properly.
