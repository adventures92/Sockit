package dev.adven.sockit.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamCommandTest {
    @Test
    fun subscribeResolvesToSubscribeWireName() {
        val command = Subscribe(JsonNull)
        assertEquals("subscribe", command.eventName)
    }

    @Test
    fun unsubscribeResolvesToUnsubscribeWireName() {
        val command = Unsubscribe(JsonNull)
        assertEquals("unsubscribe", command.eventName)
    }

    @Test
    fun socketCommandUsesCallerSuppliedEventName() {
        val command = SocketCommand("custom", buildJsonObject { })
        assertEquals("custom", command.eventName)
    }
}
