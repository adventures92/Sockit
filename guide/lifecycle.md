# Lifecycle

The rule is one sentence: **cancelling a scope stops collectors; only `close()` releases the
connection.**

Collectors are tied to whatever scope you launched them in, so they end with it. The engine is
owned by the `SocketClient`, which is not scope-aware — if you drop the reference without closing,
the socket stays open and the reconnect policy keeps firing.

## In a ViewModel

```kotlin
class QuotesViewModel : ViewModel() {
    private var client: SocketClient? = null

    fun start(token: String) {
        viewModelScope.launch {
            val c = SocketClient.connect(
                "https://example.com",
                socketOptions { auth { put("token", token) } },
            )
            client = c
            val socket = c.namespace()

            launch { socket.connectionState.collect(::render) }
            launch { socket.events("quote").collect(::handleQuote) }

            socket.openAwait()
        }
    }

    override fun onCleared() {
        client?.namespace()?.close()
        client?.close()
        client = null
    }
}
```

`viewModelScope` cancellation ends the two `collect` loops. `onCleared` is what actually tears the
connection down.

## Closing without racing

`close()` is non-blocking: it releases the client from the registry and the engine finishes tearing
down on the worker. That is usually what you want. When you need teardown to have *finished* —
a test, or a screen that immediately reconnects to a different host — use the suspending forms:

```kotlin
socket.close()
client.closeAwait()   // suspends until the engine is actually destroyed
```

## Pausing without disconnecting

`pauseReconnect()` stops the backoff timer without closing an active transport. Emits and inbound
events continue until the connection drops on its own.

```kotlin
client.pauseReconnect()    // e.g. app backgrounded, or connectivity lost
client.resumeReconnect()   // reopens immediately if a namespace still wants a connection
```

The library deliberately has no opinion about *when* to call these. It ships no lifecycle
observers and no connectivity monitoring — your app knows what backgrounded means for it, and
wiring that in the library would make the policy unavoidable.

## Multiplexing

Two `namespace()` calls on the same client share one physical connection, and so do two
`SocketClient`s built for the same origin — the registry is keyed on `scheme://host:port`.

```kotlin
val a = SocketClient.connect("https://example.com")   // opens an engine
val b = SocketClient.connect("https://example.com")   // reuses it

a.close()   // engine stays up: b still holds it
b.close()   // now it is destroyed
```

The engine is reference-counted, so the last close wins. Opt out with `forceNew = true` for an
independent engine, or `multiplex = false` to bypass the registry entirely.
