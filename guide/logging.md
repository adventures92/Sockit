# Logging

Silent by default, and sanitized when enabled.

| Tier | How | Output |
|------|-----|--------|
| **Release** | omit `logger`, or `Logger.NoOp` | Nothing |
| **App debug** | `Logger.essential { … }` | Lifecycle and error category, one tag |
| **Library development** | build from source with `-Psocketio.internalLogging=true` | Full wire and FSM detail |

## In your app

```kotlin
socketOptions {
    logger = if (BuildConfig.DEBUG) {
        Logger.essential { tag, level, message, throwable ->
            Log.d(tag, message, throwable)
        }
    } else {
        Logger.NoOp
    }
}
```

Everything is emitted under the single tag `Logger.TAG` (`"SocketIO"`), so `adb logcat -s SocketIO`
gets you all of it and nothing else. `Logger` is an interface you implement, so output goes
wherever you already send logs — there is no logging dependency to reconcile.

## What is redacted

`Logger.essential` output is sanitized before it reaches your sink: **URLs, query strings, tokens
and wire payloads are removed.** You get lifecycle transitions and error categories, not the data
that moved.

That is not configurable. Logs get attached to bug reports and shipped to crash reporters, and a
library that can leak a token through a log line is a liability regardless of how carefully it is
used.

## Do not build observability on logs

Production monitoring should read [`connectionState`](connection-state.md) and
[`errors`](errors.md) — both are structured, typed, and meant to be consumed by code. Log lines
are for a human reading a debug session; their wording is not a stable contract.

## Full wire logging

Complete Engine.IO frame and state-machine detail exists, but is **compiled out** of published
artifacts — the flag is a compile-time constant, so there is no runtime switch and no dead code in
your release build. It is available only when building the library from source:

```bash
./scripts/toggle-socketio-internal-logging.sh on
./gradlew :androidApp:installDebug   # rebuild — the flag is compile-time
```

If you are debugging something that looks like a protocol-level problem, that is the tool, and a
bug report built from it is far more useful than one built from `essential` output.
