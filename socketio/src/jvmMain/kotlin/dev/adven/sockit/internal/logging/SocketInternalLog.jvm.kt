package dev.adven.sockit.internal.logging

internal actual fun platformInternalLog(tag: String, message: String) {
    println("[$tag] $message")
}
