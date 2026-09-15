package dev.adven.sockit.api

public sealed interface SocketError {
    public data class Timeout(val phase: String) : SocketError
    public data class TlsFailure(val cause: Throwable?) : SocketError
    public data class ParseError(val raw: String) : SocketError
    public data object PingTimeout : SocketError
    public data class TransportClosed(val reason: String?) : SocketError
    public data class SendFailed(val cause: Throwable?) : SocketError

    /**
     * A namespace connection was refused by the server.
     *
     * [message] is the human-readable reason (the `message` field of the server's error, or the
     * raw payload if it was not a `{message, data}` object). [data] is the JSON-encoded `data`
     * field the server attached, or `null` when absent.
     */
    public data class ConnectError(val message: String, val data: String? = null) : SocketError
}
