# Receiving events

```kotlin
socket.events("price").collect { event ->
    // event.name, event.args
}
```

`events(name)` is a cold `Flow` over a hot buffer. Collecting late does not lose messages — events
that arrived while nobody was subscribed are buffered and delivered when you attach. That applies
across a reconnect too.

## Payloads

Each `SocketEvent` carries `name` and `args: List<SocketPayload>`. `SocketPayload` is sealed:

| Variant | On the wire |
|---------|-------------|
| `Text` | A plain string argument |
| `Json` | Numbers, booleans, objects, arrays, `null` |
| `Binary` | A binary attachment, as `kotlinx-io` `ByteString` |

```kotlin
socket.events("price").collect { event ->
    when (val payload = event.args.firstOrNull()) {
        is SocketPayload.Text -> handleText(payload.value)
        is SocketPayload.Json -> {
            val obj = payload.element as? JsonObject ?: return@collect
            handlePrice(obj["pair"]?.jsonPrimitive?.content)
        }
        is SocketPayload.Binary -> handleBytes(payload.bytes)
        null -> Unit
    }
}
```

A number or boolean you emitted comes back as `Json` holding a `JsonPrimitive`, not as `Text` —
`Text` means the wire value was genuinely a string. Once you know the schema, decode with
`kotlinx.serialization` rather than walking `JsonElement` by hand.

## Buffering

The buffer is bounded. When it fills, behaviour is configurable:

```kotlin
socketOptions {
    eventBuffer {
        capacity = 64
        overflow = EventBufferOverflow.DROP_OLDEST
    }
}
```

| Overflow | Effect |
|----------|--------|
| `SUSPEND` | Back-pressure to the engine. Nothing is lost; a slow collector slows reading |
| `DROP_OLDEST` | Keep the newest. Right for live prices, where stale values are worthless |
| `DROP_LATEST` | Keep the oldest. Right when the first occurrence is what matters |

There is no default that is correct for every stream, which is why it is a choice rather than a
constant. A slow collector under `SUSPEND` will eventually stall the connection.

## Events that ask for a reply

A server can emit an event *expecting* an acknowledgement. Those arrive with a non-null
`event.ack`:

```kotlin
socket.events("ping").collect { event ->
    event.ack?.send("pong")
}
```

Not replying leaves the server's callback hanging until its own timeout. See
[Acknowledgements](acknowledgements.md).
