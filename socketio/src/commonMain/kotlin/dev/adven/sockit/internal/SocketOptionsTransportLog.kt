package dev.adven.sockit.internal

import dev.adven.sockit.internal.logging.SocketInternalLog

internal fun logVerboseDroppedTransports(raw: List<String>, normalized: List<String>) {
    if (normalized.size >= raw.size) return
    SocketInternalLog.verbose("SocketOptions") {
        "Dropped unsupported transports: ${raw - normalized.toSet()}"
    }
}
