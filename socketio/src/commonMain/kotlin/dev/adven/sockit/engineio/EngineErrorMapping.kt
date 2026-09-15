package dev.adven.sockit.engineio

import dev.adven.sockit.api.SocketError
import dev.adven.sockit.protocol.ProtocolParseException

internal fun mapTransportError(cause: Any?): SocketError = when (cause) {
    is ProtocolParseException -> SocketError.ParseError(cause.raw)

    is Throwable -> {
        val message = cause.message.orEmpty()
        when {
            message.contains("SSL", ignoreCase = true) ||
                message.contains("TLS", ignoreCase = true) ||
                message.contains("certificate", ignoreCase = true) ->
                SocketError.TlsFailure(cause)

            else -> SocketError.SendFailed(cause)
        }
    }

    is String -> when {
        cause.contains("HTTP error", ignoreCase = true) ->
            SocketError.TransportClosed(cause)

        cause.contains("timeout", ignoreCase = true) ->
            SocketError.Timeout("transport")

        else -> SocketError.TransportClosed(cause)
    }

    else -> SocketError.TransportClosed(cause?.toString())
}
