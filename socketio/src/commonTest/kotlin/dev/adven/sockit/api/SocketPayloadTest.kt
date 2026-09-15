package dev.adven.sockit.api

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SocketPayloadTest {
    @Test
    fun rejectsUnsupportedPayloadType() {
        assertFailsWith<IllegalArgumentException> {
            encodePayload(object {})
        }
    }

    @Test
    fun encodesNumberAsJsonNumberNotString() {
        val payload = encodePayload(42)
        val element = assertIs<SocketPayload.Json>(payload).element
        val primitive = assertIs<JsonPrimitive>(element)
        assertEquals(false, primitive.isString)
        assertEquals("42", primitive.content)
    }
}
