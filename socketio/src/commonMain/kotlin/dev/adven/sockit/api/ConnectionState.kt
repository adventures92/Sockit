package dev.adven.sockit.api

public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data object Connecting : ConnectionState
    public data object Connected : ConnectionState
    public data class Reconnecting(val attempt: Int) : ConnectionState
    public data class Failed(val error: SocketError) : ConnectionState
}
