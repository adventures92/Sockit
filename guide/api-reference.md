# API reference

The generated reference lives at **[/api/](https://adventures92.github.io/Sockit/api/)**.

It is produced by Dokka from the source and published alongside this guide on every release, so it
is exhaustive and cannot drift from the code.

## What is in it

Only `dev.adven.sockit.api` — every public type, with its signatures and KDoc:

| | |
|---|---|
| `SocketClient` | Entry point and multiplex registry |
| `NamespaceSocket` | Per-namespace socket |
| `socketOptions { }` / `SocketOptions` | Configuration |
| `ConnectionState` | Lifecycle states |
| `SocketError` | Sealed failure vocabulary |
| `SocketException` | Thrown by suspending calls |
| `SocketEvent`, `SocketPayload`, `Ack` | Inbound event shape |
| `StreamCommand`, `Subscribe`, `Unsubscribe`, `SocketCommand` | Outbound commands |
| `Transports`, `EventBufferConfig`, `EventBufferOverflow`, `Logger` | Supporting types |

Internal packages — `protocol`, `engineio`, `transport`, `socketio`, `internal` — are excluded
from the reference because they are excluded from the contract. They can change in a patch release.

## Generating it locally

```bash
./gradlew :socketio:dokkaGeneratePublicationHtml   # → socketio/build/dokka/html/
```

> Use that task, not `dokkaHtml`. The latter is a disabled Dokka V1 task that produces nothing and
> still reports success.

## Which to read

This guide explains **how and why**; the reference lists **what**. If you are learning the
library, read the guide and follow links into the reference for exact signatures. If you already
know what you are looking for, the reference is faster.
