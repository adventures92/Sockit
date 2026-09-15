package dev.adven.sockit.engineio

import dev.adven.sockit.api.Logger
import dev.adven.sockit.internal.logging.SocketLog
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineDrainAccountingTest {
    @Test
    fun oversizedDrainCountIsDefensiveNoOp() {
        val buffer = ArrayDeque<OutboundEngineMessage>(listOf(OutboundEngineMessage(EnginePacket.Ping())))
        val removed = drainWriteBuffer(buffer, count = 5, log = SocketLog(Logger.NoOp))
        assertEquals(0, removed)
        assertEquals(1, buffer.size)
    }

    @Test
    fun normalDrainRemovesPackets() {
        val buffer = ArrayDeque<OutboundEngineMessage>(
            listOf(
                OutboundEngineMessage(EnginePacket.Ping()),
                OutboundEngineMessage(EnginePacket.Pong()),
            ),
        )
        val removed = drainWriteBuffer(buffer, count = 2, log = SocketLog(Logger.NoOp))
        assertEquals(2, removed)
        assertEquals(0, buffer.size)
    }
}
