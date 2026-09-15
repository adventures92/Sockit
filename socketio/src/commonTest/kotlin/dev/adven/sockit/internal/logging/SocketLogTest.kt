package dev.adven.sockit.internal.logging

import dev.adven.sockit.api.Logger
import dev.adven.sockit.api.SocketError
import kotlin.test.Test
import kotlin.test.assertEquals

class SocketLogTest {
    @Test
    fun consumerChannelAlwaysUsesUnifiedTag() {
        val tags = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(tag: String, message: String) {
                tags += tag
            }

            override fun info(tag: String, message: String) {
                tags += tag
            }

            override fun error(tag: String, message: String, throwable: Throwable?) {
                tags += tag
            }
        }
        val log = SocketLog(logger)

        log.lifecycleInfo("EngineConnection", "connected")
        log.lifecycleError("NamespaceSocket", SocketError.PingTimeout)
        log.lifecycleError("WebSocketTransport", "transport error")
        log.configDebug("SocketOptions", "dropped 1 unsupported transport(s)")

        assertEquals(List(4) { Logger.TAG }, tags)
    }
}
