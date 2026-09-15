# Sharing an HttpClient

By default the library creates its own Ktor client per platform — OkHttp on Android, Darwin on
iOS, CIO on JVM. Pass your own to reuse an existing connection pool, proxy configuration, timeouts
or interceptors:

```kotlin
socketOptions {
    httpClient = appHttpClient
}
```

## Requirements

**The WebSockets plugin must be installed**, or the upgrade cannot happen:

```kotlin
val appHttpClient = HttpClient(OkHttp) {
    install(WebSockets)
    // your timeouts, proxy, interceptors…
}
```

**You own its lifetime.** The library never closes a client you passed in — closing it while
sockets are live kills them.

```kotlin
client.close()        // Sockit releases the engine
appHttpClient.close() // you close yours, after
```

## When it is worth doing

Reuse the app's client when you need **certificate pinning**, a **corporate proxy**, custom
**timeouts**, or request logging that must cover Socket.IO traffic too. In those cases the
configuration already exists and duplicating it is how the two drift apart.

Otherwise the default is fine, and it keeps socket traffic isolated from your REST traffic — a
misconfigured interceptor cannot then break your realtime connection.

## Interaction with `trustAllCerts`

`trustAllCerts` is implemented by the library's own client factory. **If you inject an
`httpClient`, the flag is ignored** — your client's TLS configuration is used as-is.

That is the right behaviour: a client you built and configured should not have its certificate
validation silently altered by a socket option. See [TLS](tls.md).
