# Authentication

Two mechanisms, and they are not interchangeable.

## `auth` — the Socket.IO handshake

```kotlin
socketOptions {
    auth {
        put("token", jwt)
        putJsonObject("meta") { put("deviceId", 42) }
    }
}
```

Sent as the `CONNECT` packet's payload, per namespace. It is a `JsonObject`, so values can be
strings, numbers, booleans or nested objects — not just strings.

Server-side this is `socket.handshake.auth`. Rejecting it there produces a
`SocketError.ConnectError` carrying the server's message and data, and moves the namespace to
`Failed`.

This is the right place for a token in almost every case.

## `extraHeaders` — HTTP headers

```kotlin
socketOptions {
    extraHeaders { put("Authorization", listOf("Bearer $jwt")) }
}
```

Applied to the HTTP polling requests and the WebSocket upgrade. Use these when a proxy or gateway
in front of your server needs them, or when the server authenticates at the HTTP layer rather than
in the Socket.IO handshake.

Note the value is a **list** — HTTP allows repeated headers.

> On some platforms custom headers cannot be attached to a native WebSocket handshake. If you are
> websocket-only and headers seem to vanish, move the credential into `auth`, which travels in the
> Socket.IO payload and is unaffected.

## Expiry and refresh

**The library does not refresh tokens.** There is no callback invoked on expiry and no retry with
new credentials — options are an immutable snapshot taken at connect time.

When a token expires you get `ConnectError` and a `Failed` namespace. Recovery is yours:

```kotlin
socket.connectionState.collect { state ->
    if (state is ConnectionState.Failed && state.error is SocketError.ConnectError) {
        val fresh = refreshToken()
        client.close()
        client = SocketClient.connect(url, socketOptions { auth { put("token", fresh) } })
        client.namespace().openAwait()
    }
}
```

This is deliberate. A refresh hook would need to know your auth server, your retry policy and what
counts as a permanent failure — all of which live in your app.
