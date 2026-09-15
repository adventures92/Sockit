package dev.adven.sockit.engineio

import dev.adven.sockit.api.SocketError
import dev.adven.sockit.protocol.ProtocolParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EngineParseErrorMappingTest {
    @Test
    fun protocolParseExceptionMapsToParseError() {
        val mapped = mapTransportError(ProtocolParseException("bad"))
        assertEquals(SocketError.ParseError("bad"), mapped)
    }

    @Test
    fun genericThrowableMapsToSendFailed() {
        val mapped = mapTransportError(IllegalArgumentException("boom"))
        assertIs<SocketError.SendFailed>(mapped)
    }
}
