# Sockit

A **coroutine-first Socket.IO client for Kotlin Multiplatform** — Android, iOS and JVM from one
codebase.

```kotlin
implementation("io.github.adventures92:sockit:0.0.2")
```

```kotlin
val client = SocketClient.connect("https://example.com")
val socket = client.namespace()

launch { socket.events("price").collect(::render) }

socket.openAwait()
socket.emit("subscribe", "BTC-INR")
```

## What makes it different

**The protocol is implemented here.** Engine.IO v4 and Socket.IO v5 are decoded and encoded by
this library — there is no JavaScript runtime, no wrapper around a third-party client, and no
protocol dependency to go stale. The only runtime dependencies are `kotlinx-*` and Ktor.

**The API is coroutines, not callbacks.** State is a `StateFlow`, events are a `Flow`, and
connecting is a `suspend` function. There is no `on("event") { }` registry to leak, and no
callback thread to reason about.

**Threading is settled, not delegated.** Every mutation of connection state happens on a single
serial coroutine. You may call the public API from any thread; it enqueues onto that worker. The
upgrade, drain and close races that this design prevents are the hard part of a Socket.IO client,
and they are handled once rather than by each caller.

## What it is not

It does not implement a callback `on`/`emit` API, refresh your tokens, store credentials, or ship
certificate-pinning helpers. It does not support Desktop, JS or Wasm targets. See
[Limitations](limitations.md) for the full list, stated plainly rather than as a roadmap.

## How this guide is organised

[Getting started](install.md) is a straight line from dependency to first message.
[Using the client](connection-state.md) covers what you reach for daily.
[Configuration](options.md) is reference material. [Platforms](android.md) covers what differs per
target.

For exhaustive type and signature detail, see the generated
[API reference](https://adventures92.github.io/Sockit/api/).
