package dev.adven.sockit.protocol

internal sealed interface EnginePacket {
    data class Open(
        val sid: String,
        val pingInterval: Int,
        val pingTimeout: Int,
        val upgrades: List<String>,
        // 0 means the server did not advertise a limit (no polling-payload cap enforced).
        val maxPayload: Int = 0,
    ) : EnginePacket

    data object Close : EnginePacket
    data class Ping(val payload: String? = null) : EnginePacket
    data class Pong(val payload: String? = null) : EnginePacket
    data class Message(val socket: SocketPacket) : EnginePacket
    data object Upgrade : EnginePacket
    data object Noop : EnginePacket
}

internal sealed interface SocketPacket {
    val namespace: String

    data class Connect(override val namespace: String, val data: String?) : SocketPacket
    data class Disconnect(override val namespace: String = "/") : SocketPacket
    data class Event(override val namespace: String, val data: String, val id: Int? = null) : SocketPacket
    data class Ack(override val namespace: String, val id: Int?, val data: String) : SocketPacket
    data class ConnectError(override val namespace: String, val data: String) : SocketPacket
    data class BinaryEvent(
        override val namespace: String,
        val data: String,
        val attachmentCount: Int,
    ) : SocketPacket

    data class BinaryAck(
        override val namespace: String,
        val id: Int?,
        val data: String,
        val attachmentCount: Int,
    ) : SocketPacket
}
