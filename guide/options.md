# Options reference

`socketOptions { }` builds an immutable snapshot. Options are read when the client is created —
mutating the builder afterwards changes nothing, and reconfiguring means building new options and
reconnecting.

```kotlin
socketOptions {
    path = "/socket.io/"
    transports(Transports.POLLING, Transports.WEBSOCKET)
    upgrade = true

    auth { put("token", jwt) }
    extraHeaders { put("Authorization", listOf("Bearer $jwt")) }

    timeoutMs = 20_000
    ackTimeoutMs = 0

    reconnection = true
    reconnectionAttempts = Int.MAX_VALUE
    reconnectionDelayMs = 1_000
    reconnectionDelayMaxMs = 5_000
    randomizationFactor = 0.5

    multiplex = true
    forceNew = false

    trustAllCerts = false
    httpClient = null
    logger = Logger.NoOp
}
```

## Connection

| Option | Default | |
|--------|---------|---|
| `path` | `/socket.io/` | Server's Engine.IO endpoint. Change only if the server did |
| `transports(…)` | polling, websocket | Allowed transports, in preference order |
| `upgrade` | `true` | Upgrade polling → websocket when the server offers it |
| `timeoutMs` | `20_000` | Budget for the initial connection |

Transports accept library constants or plain strings — `"websocket"` works as well as
`Transports.WEBSOCKET`. Unrecognised names are dropped at `build()`, matching the JavaScript
client. An empty list fails fast with `IllegalArgumentException`.

`transports(Transports.WEBSOCKET)` alone skips polling entirely: one fewer round trip, at the cost
of failing outright on networks where only long-polling survives.

## Acknowledgements

| Option | Default | |
|--------|---------|---|
| `ackTimeoutMs` | `0` — disabled | How long `emitWithAck` waits before failing |

Zero means wait forever. Set it to something finite unless you are certain the server always
replies. See [Acknowledgements](acknowledgements.md).

## Reconnection

Covered in [Reconnection](reconnection.md).

## Event buffering

```kotlin
eventBuffer {
    capacity = 64
    overflow = EventBufferOverflow.DROP_OLDEST
}
```

See [Receiving events](receiving-events.md#buffering).

## Multiplexing

| Option | Default | |
|--------|---------|---|
| `multiplex` | `true` | Share one engine per `scheme://host:port` |
| `forceNew` | `false` | Own engine, not registered |

See [Namespaces](namespaces.md#the-registry).

## Transport and TLS

| Option | Default | |
|--------|---------|---|
| `httpClient` | `null` | Reuse your own Ktor client — see [Sharing an HttpClient](http-client.md) |
| `trustAllCerts` | `false` | **Development only** — see [TLS](tls.md) |

## Logging

`logger` defaults to `Logger.NoOp`. See [Logging](logging.md).
