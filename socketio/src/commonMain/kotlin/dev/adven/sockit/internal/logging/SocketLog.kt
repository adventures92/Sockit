package dev.adven.sockit.internal.logging

import dev.adven.sockit.api.Logger
import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.SocketOptions

internal class SocketLog(
    private val consumer: Logger,
) {
    fun lifecycleInfo(tag: String, message: String) {
        consumer.info(Logger.TAG, prefixed(tag, LogSanitizer.sanitizeMessage(message)))
    }

    fun lifecycleError(tag: String, error: SocketError) {
        consumer.error(Logger.TAG, prefixed(tag, LogSanitizer.summarize(error)))
    }

    fun lifecycleError(tag: String, message: String, throwable: Throwable? = null) {
        consumer.error(Logger.TAG, prefixed(tag, LogSanitizer.sanitizeMessage(message)))
        if (throwable != null) {
            SocketInternalLog.verbose(tag, message, throwable)
        }
    }

    fun configDebug(tag: String, message: String) {
        consumer.debug(Logger.TAG, prefixed(tag, message))
    }

    fun devVerbose(tag: String, message: () -> String) {
        SocketInternalLog.verbose(tag, message)
    }

    fun devVerbose(tag: String, message: String) {
        SocketInternalLog.verbose(tag, message)
    }

    fun devVerbose(tag: String, message: String, throwable: Throwable?) {
        SocketInternalLog.verbose(tag, message, throwable)
    }

    private fun prefixed(tag: String, message: String): String = "$tag: $message"
}

internal val SocketOptions.socketLog: SocketLog
    get() = SocketLog(logger)
