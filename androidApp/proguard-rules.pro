# Demo app release rules (Compose + KMP shared module).

-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Kotlin coroutines (main dispatcher on Android).
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
