package dev.adven.sockit.internal.logging

import android.util.Log

internal actual fun platformInternalLog(tag: String, message: String) {
    Log.d(tag, message)
}
