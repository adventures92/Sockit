package dev.adven.sockit.internal.logging

internal expect fun platformInternalLog(tag: String, message: String)

internal object SocketInternalLog {
    inline fun verbose(tag: String, message: () -> String) {
        if (INTERNAL_LOG_ENABLED) {
            platformInternalLog(tag, message())
        }
    }

    fun verbose(tag: String, message: String) {
        if (INTERNAL_LOG_ENABLED) {
            platformInternalLog(tag, message)
        }
    }

    fun verbose(tag: String, message: String, throwable: Throwable?) {
        if (!INTERNAL_LOG_ENABLED) return
        val detail = if (throwable != null) {
            "$message (${throwable::class.simpleName}: ${throwable.message})"
        } else {
            message
        }
        platformInternalLog(tag, detail)
    }
}
