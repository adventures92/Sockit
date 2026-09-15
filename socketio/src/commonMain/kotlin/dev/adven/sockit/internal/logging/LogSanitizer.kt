package dev.adven.sockit.internal.logging

import dev.adven.sockit.api.SocketError

internal object LogSanitizer {
    private val urlPattern = Regex("""\b(?:https?|wss?)://[^\s"'`]+""")
    private val jwtPattern = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
    private val sidPattern = Regex("""\bsid=[^\s,]+""", RegexOption.IGNORE_CASE)

    fun sanitizeMessage(message: String): String = message
        .replace(urlPattern, "<url>")
        .replace(jwtPattern, "<token>")
        .replace(sidPattern, "sid=<redacted>")

    fun summarize(error: SocketError): String = when (error) {
        is SocketError.Timeout -> "timeout phase=${error.phase}"
        is SocketError.TlsFailure -> "tls failure"
        is SocketError.ParseError -> "parse error"
        is SocketError.PingTimeout -> "ping timeout"
        is SocketError.TransportClosed -> "transport closed"
        is SocketError.SendFailed -> "send failed"
        is SocketError.ConnectError -> "connect error"
    }

    fun droppedTransportsCount(dropped: Int): String = "dropped $dropped unsupported transport(s)"
}
