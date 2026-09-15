package dev.adven.sockit.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EngineIoCodecTest {
    @Test
    fun encodePing() {
        assertEquals("2", EngineIoCodec.encode(EnginePacket.Ping()))
    }

    @Test
    fun encodePong() {
        assertEquals("3", EngineIoCodec.encode(EnginePacket.Pong()))
    }

    @Test
    fun encodeCloseAndUpgrade() {
        assertEquals("1", EngineIoCodec.encode(EnginePacket.Close))
        assertEquals("5", EngineIoCodec.encode(EnginePacket.Upgrade))
        assertEquals("6", EngineIoCodec.encode(EnginePacket.Noop))
    }

    @Test
    fun decodeOpen() {
        val raw = """0{"sid":"abc","upgrades":["websocket"],"pingInterval":25000,"pingTimeout":20000}"""
        val packet = EngineIoCodec.decode(raw) as EnginePacket.Open
        assertEquals("abc", packet.sid)
        assertEquals(25000, packet.pingInterval)
        assertEquals(20000, packet.pingTimeout)
        assertEquals(listOf("websocket"), packet.upgrades)
    }

    @Test
    fun encodeOpenRoundTrip() {
        val open = EnginePacket.Open(
            sid = "abc",
            pingInterval = 25000,
            pingTimeout = 20000,
            upgrades = listOf("websocket"),
        )
        val decoded = EngineIoCodec.decode(EngineIoCodec.encode(open)) as EnginePacket.Open
        assertEquals(open, decoded)
    }

    @Test
    fun decodeOpenParsesMaxPayload() {
        val raw =
            """0{"sid":"abc","upgrades":[],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}"""
        val packet = EngineIoCodec.decode(raw) as EnginePacket.Open
        assertEquals(1000000, packet.maxPayload)
    }

    @Test
    fun decodeOpenDefaultsMaxPayloadToZeroWhenAbsent() {
        val raw = """0{"sid":"abc","upgrades":[],"pingInterval":25000,"pingTimeout":20000}"""
        val packet = EngineIoCodec.decode(raw) as EnginePacket.Open
        assertEquals(0, packet.maxPayload)
    }

    @Test
    fun pollingBatchFitSplitsByMaxPayload() {
        val msgs = List(3) {
            OutboundEngineMessage(EnginePacket.Message(SocketPacket.Event("/", """["ev"]""")))
        }
        // Tie expectations to the codec's own output so the byte accounting stays self-consistent.
        val oneLen = EngineIoCodec.encodePollingBatch(msgs.subList(0, 1)).encodeToByteArray().size
        val twoLen = EngineIoCodec.encodePollingBatch(msgs.subList(0, 2)).encodeToByteArray().size

        assertEquals(1, EngineIoCodec.pollingBatchFit(msgs, oneLen))
        assertEquals(2, EngineIoCodec.pollingBatchFit(msgs, twoLen))
        assertEquals(3, EngineIoCodec.pollingBatchFit(msgs, 10_000))
    }

    @Test
    fun pollingBatchFitAlwaysSendsAtLeastOne() {
        val msgs = List(2) {
            OutboundEngineMessage(EnginePacket.Message(SocketPacket.Event("/", """["ev"]""")))
        }
        assertEquals(2, EngineIoCodec.pollingBatchFit(msgs, 0)) // 0 = no limit
        assertEquals(1, EngineIoCodec.pollingBatchFit(msgs, 1)) // too small, still makes progress
    }

    @Test
    fun exceedsMaxPayloadDetectsOversizedSingleMessage() {
        val small = OutboundEngineMessage(EnginePacket.Message(SocketPacket.Event("/", """["ev"]""")))
        val big = OutboundEngineMessage(
            EnginePacket.Message(SocketPacket.Event("/", """["ev","${"x".repeat(500)}"]""")),
        )

        assertEquals(false, EngineIoCodec.exceedsMaxPayload(small, 10_000))
        assertEquals(true, EngineIoCodec.exceedsMaxPayload(big, 50))
        assertEquals(false, EngineIoCodec.exceedsMaxPayload(big, 0)) // 0 = no limit
    }

    @Test
    fun decodeMessageWrapsSocketIoPacket() {
        val raw = """42["message","hello"]"""
        val packet = EngineIoCodec.decode(raw) as EnginePacket.Message
        val event = packet.socket as SocketPacket.Event
        assertEquals("/", event.namespace)
        assertEquals("""["message","hello"]""", event.data)
    }

    @Test
    fun encodeBatchUsesRecordSeparator() {
        val batch = EngineIoCodec.encodeBatch(
            listOf(
                EnginePacket.Ping(),
                EnginePacket.Pong(),
            ),
        )
        assertEquals("2\u001e3", batch)
    }

    @Test
    fun decodeBatchWithRecordSeparator() {
        val open = """0{"sid":"abc","upgrades":[],"pingInterval":25000,"pingTimeout":20000}"""
        val event = """42["message","hello"]"""
        val packets = EngineIoCodec.decodeBatch("$open\u001e$event")
        assertEquals(2, packets.size)
        assertIs<EnginePacket.Open>(packets[0])
        assertIs<EnginePacket.Message>(packets[1])
    }

    @Test
    fun decodeBatchConcatenatedWithoutSeparator() {
        val raw = """40{"sid":"0_W1hJWvCHgoG_ktAAAD"}42["message","hello client"]"""
        val packets = EngineIoCodec.decodeBatch(raw)
        assertEquals(2, packets.size)

        val connect = (packets[0] as EnginePacket.Message).socket as SocketPacket.Connect
        assertEquals("/", connect.namespace)
        assertEquals("""{"sid":"0_W1hJWvCHgoG_ktAAAD"}""", connect.data)

        val event = (packets[1] as EnginePacket.Message).socket as SocketPacket.Event
        assertEquals("""["message","hello client"]""", event.data)
    }

    @Test
    fun encodeMessageWrapsSocketIoPacket() {
        val encoded = EngineIoCodec.encode(
            EnginePacket.Message(SocketPacket.Event("/", """["ev","x"]""")),
        )
        assertEquals("""42["ev","x"]""", encoded)
    }

    @Test
    fun encodeDecodeProbePingPong() {
        val probePing = EnginePacket.Ping(PROBE)
        val probePong = EnginePacket.Pong(PROBE)
        assertEquals("2probe", EngineIoCodec.encode(probePing))
        assertEquals("3probe", EngineIoCodec.encode(probePong))
        assertEquals(probePing, EngineIoCodec.decode("2probe"))
        assertEquals(probePong, EngineIoCodec.decode("3probe"))
    }

    companion object {
        private const val PROBE = "probe"
    }
}
