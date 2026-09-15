package dev.adven.sockit.protocol

import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Executable conformance checks against the official Socket.IO v5 / Engine.IO v4 protocol
 * test-suite (github.com/socketio/socket.io-protocol → `test-suite/test-suite.js`).
 *
 * The upstream suite is a client that exercises a *server*; our client is its inverse, so each
 * test here asserts that our codecs PRODUCE the exact bytes that suite expects a client to send
 * and CONSUME the exact bytes it expects a server to send. Wire vectors are copied verbatim from
 * the suite so a future protocol-revision change surfaces here first.
 *
 * All vectors are shown at the Engine.IO layer (each Socket.IO packet is wrapped in an Engine.IO
 * `message`, hence the leading `4`).
 */
@OptIn(UnsafeByteStringApi::class)
class ProtocolConformanceTest {
    // ---------------------------------------------------------------------------------------------
    // Socket.IO protocol > connect
    // ---------------------------------------------------------------------------------------------

    @Test
    fun connectToMainNamespace() {
        assertEquals("40", engineWire(SocketPacket.Connect("/", null)))
    }

    @Test
    fun connectToMainNamespaceWithPayload() {
        assertEquals(
            """40{"token":"123"}""",
            engineWire(SocketPacket.Connect("/", """{"token":"123"}""")),
        )
    }

    @Test
    fun connectToCustomNamespace() {
        assertEquals("40/custom,", engineWire(SocketPacket.Connect("/custom", null)))
    }

    @Test
    fun connectToCustomNamespaceWithPayload() {
        assertEquals(
            """40/custom,{"token":"abc"}""",
            engineWire(SocketPacket.Connect("/custom", """{"token":"abc"}""")),
        )
    }

    @Test
    fun decodeConnectErrorForUnknownNamespace() {
        val connectError = engineDecodeSocket("""44/random,{"message":"Invalid namespace"}""")
            as SocketPacket.ConnectError
        assertEquals("/random", connectError.namespace)
        assertEquals("""{"message":"Invalid namespace"}""", connectError.data)
    }

    // ---------------------------------------------------------------------------------------------
    // Socket.IO protocol > disconnect
    // ---------------------------------------------------------------------------------------------

    @Test
    fun disconnectFromMainNamespace() {
        assertEquals("41", engineWire(SocketPacket.Disconnect("/")))
    }

    @Test
    fun disconnectFromCustomNamespace() {
        assertEquals("41/custom,", engineWire(SocketPacket.Disconnect("/custom")))
        // A server may omit the trailing comma; the client must still decode it.
        assertEquals(SocketPacket.Disconnect("/custom"), SocketIoCodec.decode("1/custom"))
    }

    // ---------------------------------------------------------------------------------------------
    // Socket.IO protocol > message
    // ---------------------------------------------------------------------------------------------

    @Test
    fun sendPlainTextPacket() {
        val data = """["message",1,"2",{"3":[true]}]"""
        assertEquals("42$data", engineWire(SocketPacket.Event("/", data)))

        val decoded = engineDecodeSocket("42$data") as SocketPacket.Event
        assertEquals(data, decoded.data)
        assertEquals(null, decoded.id)
    }

    @Test
    fun sendPlainTextPacketWithAck() {
        // Inbound event carrying ack id 456.
        val event = engineDecodeSocket("""42456["message-with-ack",1,"2",{"3":[false]}]""")
            as SocketPacket.Event
        assertEquals(456, event.id)

        // The acknowledgement the client must send back.
        val ackData = """[1,"2",{"3":[false]}]"""
        assertEquals("43456$ackData", engineWire(SocketPacket.Ack("/", 456, ackData)))
    }

    @Test
    fun sendPacketWithBinaryAttachments() {
        val (wire, frames) = BinaryAssembler.encodeBinaryEvent(
            namespace = "/",
            eventName = "message",
            attachments = listOf(bytes(1, 2, 3), bytes(4, 5, 6)),
        )
        assertEquals(
            """452-["message",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""",
            "4$wire",
        )
        assertEquals(2, frames.size)
    }

    @Test
    fun sendPacketWithBinaryAttachmentsAndAck() {
        // Response to an inbound BINARY_EVENT carrying ack id 789 (spec vector).
        val (wire, frames) = BinaryAssembler.encodeBinaryAck(
            namespace = "/",
            ackId = 789,
            attachments = listOf(bytes(1, 2, 3), bytes(4, 5, 6)),
        )
        assertEquals(
            """462-789[{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""",
            "4$wire",
        )
        assertEquals(2, frames.size)
    }

    @Test
    fun rejectsUnknownSocketPacketType() {
        // Engine.IO `message` "4abc" → Socket.IO payload "abc": unparseable packet type.
        assertFailsWith<IllegalArgumentException> { SocketIoCodec.decode("abc") }
    }

    @Test
    fun rejectsUnknownEnginePacketType() {
        assertFailsWith<IllegalArgumentException> { EngineIoCodec.decode("9") }
    }

    private fun engineWire(socket: SocketPacket): String = EngineIoCodec.encode(EnginePacket.Message(socket))

    private fun engineDecodeSocket(raw: String): SocketPacket = (EngineIoCodec.decode(raw) as EnginePacket.Message).socket

    private fun bytes(vararg values: Int) = UnsafeByteStringOperations.wrapUnsafe(ByteArray(values.size) { values[it].toByte() })
}
