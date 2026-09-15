package dev.adven.sockit.protocol

import kotlinx.io.bytestring.ByteString

internal data class OutboundEngineMessage(
    val packet: EnginePacket,
    val binaryAttachments: List<ByteString> = emptyList(),
)
