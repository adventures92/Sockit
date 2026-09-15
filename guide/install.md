# Install

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.adventures92:sockit:0.0.2")
        }
    }
}
```

That single line covers every target. The library publishes five Maven modules and Gradle module
metadata routes each target to the right one — you never name a platform artifact yourself.

| Module | Artifact | Used by |
|--------|----------|---------|
| `sockit` | `.jar` + `.module` | what `commonMain` resolves |
| `sockit-android` | `.aar` | Android |
| `sockit-iosarm64` | `.klib` | iOS device |
| `sockit-iossimulatorarm64` | `.klib` | iOS simulator (Apple silicon) |
| `sockit-jvm` | `.jar` | JVM |

## Requirements

| | |
|---|---|
| Kotlin | 2.4+ |
| Android | minSdk 24 |
| iOS | arm64, simulator arm64 (Apple silicon) |
| JVM | 11+ |
| Server | Engine.IO v4 · Socket.IO v5 — the `socket.io` 4.x line |

There is no `iosX64` target, so Intel Mac simulators are not covered.

## What comes with it

These arrive transitively; you do not declare them:

- `kotlinx-coroutines-core` — `Flow`, `StateFlow`, `suspend`
- `ktor-client-core` + `ktor-client-websockets` — transport
- `kotlinx-serialization-json` — `JsonElement` appears in the public API
- `kotlinx-io-core` — `ByteString` for binary payloads
- a Ktor engine per platform — OkHttp on Android, Darwin on iOS, CIO on JVM

## Only `api` is public

Everything you can import lives in `dev.adven.sockit.api`. Every other package is `internal` and
not part of the compatibility contract — it can change in a patch release. The public surface is
guarded by Binary Compatibility Validator on both the JVM ABI and the iOS klibs, so an accidental
break fails the build rather than reaching you.
