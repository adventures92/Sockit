package dev.adven.sockit.protocol

import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(UnsafeByteStringApi::class)
class BinaryAssemblerTest {
    @Test
    fun reassemblesBinaryEventFromPlaceholderAndFrames() {
        var completed = false
        var ack = false
        var ackId: Int? = -1
        var data: String? = null

        val payload = """["baz",{"_placeholder":true,"num":0}]"""
        val packet = SocketPacket.BinaryEvent("/", payload, attachmentCount = 1)
        val assembler = BinaryAssembler(packet) { isAck, id, raw, attachments ->
            completed = true
            ack = isAck
            ackId = id
            data = BinaryAssembler.reassemble(raw, attachments)
        }

        val bytes = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assembler.add(bytes)

        assertTrue(completed)
        assertFalse(ack)
        assertNull(ackId)
        assertEquals("""["baz","01020304"]""", data)
        assertTrue(assembler.isComplete())
    }

    @Test
    fun reassemblesBinaryAckWithAckId() {
        var ackId: Int? = null
        val payload = """["bar",{"_placeholder":true,"num":0}]"""
        val packet = SocketPacket.BinaryAck("/", 15, payload, attachmentCount = 1)
        val assembler = BinaryAssembler(packet) { isAck, id, _, _ ->
            assertTrue(isAck)
            ackId = id
        }

        assembler.add(UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x0A)))
        assertEquals(15, ackId)
    }

    @Test
    fun waitsUntilAllAttachmentsArrive() {
        val payload =
            """["baz",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]"""
        val packet = SocketPacket.BinaryEvent("/admin", payload, attachmentCount = 2)
        val assembler = BinaryAssembler(packet) { _, _, _, _ -> }

        assembler.add(UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x01, 0x02)))
        assertFalse(assembler.isComplete())

        assembler.add(UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x03, 0x04)))
        assertTrue(assembler.isComplete())
    }

    @Test
    fun encodeBinaryEventRoundTrip() {
        val attachment = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val (wire, frames) = BinaryAssembler.encodeBinaryEvent(
            namespace = "/",
            eventName = "baz",
            attachments = listOf(attachment),
            JsonPrimitive("extra"),
        )

        assertEquals("""51-["baz","extra",{"_placeholder":true,"num":0}]""", wire)
        assertEquals(1, frames.size)

        val decoded = SocketIoCodec.decode(wire) as SocketPacket.BinaryEvent
        var reassembled: String? = null
        val assembler = BinaryAssembler(decoded) { _, _, raw, attachments -> reassembled = BinaryAssembler.reassemble(raw, attachments) }
        assembler.add(frames.first())

        assertEquals("""["baz","extra","01020304"]""", reassembled)
    }

    @Test
    fun encodeBinaryAckRoundTrip() {
        val attachment = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x0A, 0x0B))
        val (wire, frames) = BinaryAssembler.encodeBinaryAck(
            namespace = "/",
            ackId = 7,
            attachments = listOf(attachment),
            jsonArgs = listOf(JsonPrimitive("ok")),
        )

        assertEquals("""61-7["ok",{"_placeholder":true,"num":0}]""", wire)
        assertEquals(1, frames.size)

        val decoded = SocketIoCodec.decode(wire) as SocketPacket.BinaryAck
        assertEquals(7, decoded.id)
        assertEquals(1, decoded.attachmentCount)

        var reassembled: String? = null
        val assembler = BinaryAssembler(decoded) { _, _, raw, attachments -> reassembled = BinaryAssembler.reassemble(raw, attachments) }
        assembler.add(frames.first())
        assertEquals("""["ok","0a0b"]""", reassembled)
    }

    @Test
    fun companionReassembleMatchesAssembler() {
        val payload = """[1,{"_placeholder":true,"num":0}]"""
        val bytes = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
        val fromCompanion = BinaryAssembler.reassemble(payload, listOf(bytes))

        var fromAssembler: String? = null
        val packet = SocketPacket.BinaryEvent("/", payload, attachmentCount = 1)
        BinaryAssembler(packet) { _, _, raw, attachments -> fromAssembler = BinaryAssembler.reassemble(raw, attachments) }.add(bytes)

        assertEquals(fromCompanion, fromAssembler)
    }
}
