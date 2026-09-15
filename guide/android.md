# Android

`minSdk 24`. The engine is Ktor's OkHttp.

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Connecting to a **cleartext** `http://` or `ws://` endpoint — a local dev server, an emulator host
on `10.0.2.2` — additionally needs a network-security config, because cleartext is blocked by
default since API 28. Scope it to the debug build rather than the manifest your release uses.

## R8 / ProGuard

Nothing to add. The AAR ships `consumer-rules.pro`, merged automatically into your app's R8
configuration. It covers the public `api` package and the one `@Serializable` type the library
uses internally. Ktor and OkHttp bring their own rules transitively.

Verify with a minified build:

```bash
./gradlew :app:assembleRelease
```

## Threading

Call the API from any thread, including the main thread. Every mutation of connection state is
marshalled onto a single internal worker, so nothing blocks the caller and nothing races.

Collect on whatever dispatcher suits the consumer — `Dispatchers.Main` for UI is fine, since
nothing in the library does blocking work on the collecting thread.

## Lifecycle

The library ships **no** lifecycle observers. It does not know that your Activity was destroyed or
that the app was backgrounded, and it will keep the connection and the reconnect policy alive
until you close it.

Tie it to a `ViewModel` — see [Lifecycle](lifecycle.md) — and consider
[`pauseReconnect()`](lifecycle.md#pausing-without-disconnecting) on background rather than a full
close, so returning to the foreground does not pay for a fresh handshake.

## A note on the demo app

`androidApp` in this repository is a demo and the encapsulation gate that proves `internal` types
cannot leak to consumers. It is not a recommended architecture — it is the smallest thing that
exercises the API.
