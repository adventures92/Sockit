# TLS

HTTPS and WSS work out of the box with the platform's trust store. Nothing to configure.

## Certificate pinning

Not provided by this library. Configure it on an [injected `HttpClient`](http-client.md), using
the engine's own mechanism:

```kotlin
// Android — OkHttp
val client = HttpClient(OkHttp) {
    install(WebSockets)
    engine {
        preconfigured = OkHttpClient.Builder()
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build(),
            )
            .build()
    }
}
```

Shipping pinning helpers was considered and rejected: the useful part of pinning is pin rotation
and backup-pin policy, which belongs to your release process rather than to a socket client.

## `trustAllCerts` — development only

```kotlin
socketOptions { trustAllCerts = true }   // never in a shipped build
```

Disables certificate **and** hostname verification, so any certificate is accepted. It exists for
local development against a self-signed server.

> **Before you ship.** The implementation — a no-op `X509TrustManager` and a permissive
> `HostnameVerifier` on Android, an accept-all challenge handler on Darwin — is compiled into the
> released artifact whether or not you set the flag. Google Play's pre-launch review flags that
> pattern. If your release process cannot absorb that finding, do not use the flag at all:
> inject your own `HttpClient` and configure a development trust store on it, which keeps the
> permissive code in your debug source set rather than in a shipped library.

It is also ignored when you inject an `httpClient`, so it cannot silently weaken a client you
configured yourself.
