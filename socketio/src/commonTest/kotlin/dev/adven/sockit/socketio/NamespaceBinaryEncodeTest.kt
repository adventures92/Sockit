package dev.adven.sockit.socketio

import dev.adven.sockit.protocol.BinaryAssembler
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import dev.adven.sockit.protocol.SocketIoCodec
import dev.adven.sockit.protocol.SocketPacket
import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(UnsafeByteStringApi::class)
class NamespaceBinaryEncodeTest {
    @Test
    fun byteStringPayloadProducesBinaryEventWithAttachments() {
        val bytes = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x0A, 0x0B, 0x0C))
        val (wire, frames) = BinaryAssembler.encodeBinaryEvent(
            namespace = "/",
            eventName = "echoBinary",
            attachments = listOf(bytes),
            JsonPrimitive("meta"),
        )
        val socketPacket = SocketIoCodec.decode(wire)
        assertTrue(socketPacket is SocketPacket.BinaryEvent)
        assertEquals(1, (socketPacket as SocketPacket.BinaryEvent).attachmentCount)
        assertEquals(1, frames.size)

        val message = OutboundEngineMessage(EnginePacket.Message(socketPacket), frames)
        assertEquals(1, message.binaryAttachments.size)
    }
}
