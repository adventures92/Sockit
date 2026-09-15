# Emitting

```kotlin
socket.emit("custom", "arg1", 42)
socket.emit("binary", byteString)
```

Accepted argument types: `String`, `Boolean`, `Number`, `JsonElement`, `ByteString`, and `null`.
Anything else throws at the call site rather than failing on the wire.

## `emit` vs `emitAwait`

| | Returns when | Throws on |
|---|---|---|
| `emit` | Immediately | Nothing — failures go to [`errors`](errors.md) |
| `emitAwait` | The event is **queued** | Pre-queue rejection only |

The distinction matters more than it looks. `emitAwait` completing means the event reached the
outbound queue — not the server, not even the socket. A write that fails afterwards is reported on
`errors` as `SendFailed`; it does **not** retroactively fail the call that queued it.

```kotlin
try {
    socket.emitAwait("important", payload)
} catch (e: SocketException.SendFailed) {
    // reserved event name, or an argument that could not be encoded
}
```

So neither call confirms delivery. If you need confirmation, the server has to tell you — see
[Acknowledgements](acknowledgements.md). This is the same guarantee the JavaScript client gives;
it is stated plainly here because "await" invites the wrong assumption.

## Typed commands

`Subscribe` and `Unsubscribe` carry library-fixed event names — only the JSON body is yours:

```kotlin
socket.emit(Subscribe(buildJsonObject { put("pair", "BTC-INR") }))
socket.emit(Unsubscribe(buildJsonObject { put("pair", "BTC-INR") }))
```

The event-name constants are deliberately `internal`. If your server uses different names, use
`SocketCommand`, which takes the name explicitly:

```kotlin
socket.emit(SocketCommand("watch", buildJsonObject { put("x", 1) }))
```

Or implement `StreamCommand` yourself for a type your own code can pattern-match on. Typed
commands exist so a subscription is a value you can pass around and test, rather than a string
literal repeated at every call site.

## Binary

`ByteString` arguments become real binary attachments — Socket.IO `BINARY_EVENT` frames, not
base64 in JSON:

```kotlin
socket.emit("upload", ByteString(bytes))
```

Mixing binary and scalar arguments preserves their order on the wire.
