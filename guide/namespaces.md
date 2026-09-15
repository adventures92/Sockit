# Namespaces

A namespace is a logical channel over one physical connection. `client.namespace()` gives you the
default `/`; pass a path for anything else.

```kotlin
val root = client.namespace()
val admin = client.namespace("/admin")

root.open()
admin.openAwait()
```

Both share a single engine, so opening a second namespace costs no extra socket, handshake or
heartbeat.

## Independent state, shared transport

Each namespace has its own `connectionState`, `errors` and event streams. One can be `Failed` —
rejected auth on `/admin`, say — while another stays `Connected`.

What they share is the transport. If the *engine* drops, every namespace on it goes
`Reconnecting` together, and each re-sends its own `CONNECT` on reopen.

`SocketClient.connectionState` aggregates across namespaces, which is what you want for a single
connection indicator.

## The registry

Clients built for the same origin share an engine too — the registry is keyed on
`scheme://host:port`, and `/a` and `/b` on one host are one connection.

```kotlin
val a = SocketClient.connect("https://example.com")
val b = SocketClient.connect("https://example.com")   // same engine
```

Reference-counted: the engine is destroyed when the last client closes.

Opt out when you need genuine isolation — separate credentials, or a connection whose failure must
not affect another:

```kotlin
socketOptions {
    forceNew = true    // own engine, not registered
    // or
    multiplex = false  // bypass the registry entirely
}
```

Calling `namespace("/x")` twice returns the same instance, so it is safe to call wherever you need
it rather than threading a reference through your code.
