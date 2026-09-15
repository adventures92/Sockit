package dev.adven.sockit.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class SocketIoCodecTest {
    @Test
    fun encodeDecodeConnectOnDefaultNamespace() {
        val packet = SocketPacket.Connect("/", null)
        assertEquals("0", SocketIoCodec.encode(packet))
        assertEquals(packet, SocketIoCodec.decode("0"))
    }

    @Test
    fun encodeDecodeConnectWithDataOnDefaultNamespace() {
        val data = """{"token":"abc"}"""
        val packet = SocketPacket.Connect("/", data)
        assertEquals("0$data", SocketIoCodec.encode(packet))
        assertEquals(packet, SocketIoCodec.decode("0$data"))
    }

    @Test
    fun encodeDecodeConnectOnAdminNamespace() {
        val data = """{"sid":"oSO0OpakMV_3jnilAAAA"}"""
        val packet = SocketPacket.Connect("/admin", data)
        assertEquals("0/admin,$data", SocketIoCodec.encode(packet))
        assertEquals(packet, SocketIoCodec.decode("0/admin,$data"))
    }

    @Test
    fun encodeDecodeEventOnDefaultNamespace() {
        val packet = SocketPacket.Event("/", """["foo"]""")
        assertEquals("""2["foo"]""", SocketIoCodec.encode(packet))
        assertEquals(packet, SocketIoCodec.decode("""2["foo"]"""))
    }

    @Test
    fun encodeDecodeEventOnAdminNamespace() {
        val packet = SocketPacket.Event("/admin", """["bar"]""")
        assertEquals("""2/admin,["bar"]""", SocketIoCodec.encode(packet))
        assertEquals(packet, SocketIoCodec.decode("""2/admin,["bar"]"""))
    }

    @Test
    fun encodeEventBuildsAdminWireFormat() {
        val payload = buildJsonObject { put("pair", "BTC-INR") }
        val encoded = SocketIoCodec.encodeEvent("/admin", "ev", payload)
        assertEquals("""2/admin,["ev",{"pair":"BTC-INR"}]""", encoded)
    }

    @Test
    fun encodeDecodeEventWithAckId() {
        val packet = SocketPacket.Event("/", """["foo"]""", id = 12)
        val wire = "212[\"foo\"]"
        assertEquals(packet, SocketIoCodec.decode(wire))
        assertEquals(wire, SocketIoCodec.encode(packet))
    }

    @Test
    fun encodeDecodeAckOnDefaultNamespace() {
        val packet = SocketPacket.Ack("/", 12, """[]""")
        assertEquals("312[]", SocketIoCodec.encode(packet))
        assertEquals(packet, SocketIoCodec.decode("312[]"))
    }

    @Test
    fun encodeDecodeAckOnAdminNamespace() {
        val packet = SocketPacket.Ack("/admin", 13, """["bar"]""")
        assertEquals("""3/admin,13["bar"]""", SocketIoCodec.encode(packet))
        assertEquals(packet, SocketIoCodec.decode("""3/admin,13["bar"]"""))
    }

    @Test
    fun encodeDecodeDisconnect() {
        assertEquals("1", SocketIoCodec.encode(SocketPacket.Disconnect()))
        assertEquals(SocketPacket.Disconnect(), SocketIoCodec.decode("1"))

        val adminDisconnect = SocketPacket.Disconnect("/admin")
        assertEquals("1/admin,", SocketIoCodec.encode(adminDisconnect))
        assertEquals(adminDisconnect, SocketIoCodec.decode("1/admin,"))
    }

    @Test
    fun encodeDecodeConnectError() {
        val packet = SocketPacket.ConnectError("/", """{"message":"Not authorized"}""")
        assertEquals("""4{"message":"Not authorized"}""", SocketIoCodec.encode(packet))
        assertEquals(packet, SocketIoCodec.decode("""4{"message":"Not authorized"}"""))
    }

    @Test
    fun encodeDecodeBinaryEvent() {
        val data = """["baz",{"_placeholder":true,"num":0}]"""
        val packet = SocketPacket.BinaryEvent("/", data, attachmentCount = 1)
        assertEquals("""51-$data""", SocketIoCodec.encode(packet))
        val decoded = SocketIoCodec.decode("""51-$data""") as SocketPacket.BinaryEvent
        assertEquals(packet.namespace, decoded.namespace)
        assertEquals(packet.data, decoded.data)
        assertEquals(1, decoded.attachmentCount)
    }

    @Test
    fun encodeDecodeBinaryAck() {
        val data = """["bar",{"_placeholder":true,"num":0}]"""
        val packet = SocketPacket.BinaryAck("/", 15, data, attachmentCount = 1)
        assertEquals("""61-15$data""", SocketIoCodec.encode(packet))
        val decoded = SocketIoCodec.decode("""61-15$data""") as SocketPacket.BinaryAck
        assertEquals(15, decoded.id)
        assertEquals(1, decoded.attachmentCount)
        assertEquals(data, decoded.data)
    }

    @Test
    fun encodeDecodeBinaryEventWithMultipleAttachmentsOnAdminNamespace() {
        val data =
            """["baz",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]"""
        val wire = "52-/admin,$data"
        val decoded = SocketIoCodec.decode(wire) as SocketPacket.BinaryEvent
        assertEquals("/admin", decoded.namespace)
        assertEquals(2, decoded.attachmentCount)
        assertEquals(data, decoded.data)
    }
}
