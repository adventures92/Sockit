package dev.adven.sockit.internal.logging

import platform.Foundation.NSLog

internal actual fun platformInternalLog(tag: String, message: String) {
    NSLog("[%s] %s", tag, message)
}
