# iOS

Targets `iosArm64` (device) and `iosSimulatorArm64` (Apple silicon simulator). The engine is
Ktor's Darwin.

**There is no `iosX64` target**, so Intel Mac simulators are not supported. Adding a target is
additive and safe; removing a published one breaks consumers, so the list is treated as a
commitment rather than a default.

## App Transport Security

ATS blocks cleartext by default. `https://` and `wss://` need nothing. For a local `ws://` server
during development, add an ATS exception to your **debug** Info.plist only — never the one you
ship.

## Threading

Kotlin/Native's memory model permits sharing across threads, and this library is designed for it:
all connection state lives on one internal worker and the public API is safe to call from any
thread, including the main one.

From Swift, the generated API surfaces `suspend` functions as completion handlers or `async`
depending on your interop settings; `Flow` needs a bridge — either one you write or a library such
as SKIE or KMP-NativeCoroutines. That choice is yours; the library does not impose one.

## Background

iOS suspends network activity when your app backgrounds, and the connection will drop. The library
does not detect this — it has no `UIApplication` awareness.

Handle it where you already observe lifecycle:

```kotlin
client.pauseReconnect()    // entering background
client.resumeReconnect()   // returning to foreground
```

Without pausing, the reconnect policy will retry against a suspended network stack and burn its
attempt budget for nothing.

## Building the demo

```bash
open iosApp/  # then run from Xcode
```

The shared Compose UI is built by Gradle as a framework; Xcode consumes it. The demo exists to
exercise the API, not to model an app architecture.
