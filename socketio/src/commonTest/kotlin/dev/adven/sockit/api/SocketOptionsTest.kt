package dev.adven.sockit.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class SocketOptionsTest {
    @Test
    fun defaultsMatchSpec() {
        val options = socketOptions { }
        assertEquals("/socket.io/", options.path)
        assertEquals(listOf(Transports.POLLING, Transports.WEBSOCKET), options.transports)
        assertTrue(options.upgrade)
        assertTrue(options.auth.isEmpty())
        assertTrue(options.extraHeaders.isEmpty())
        assertEquals(20_000L, options.timeoutMs)
        assertTrue(options.reconnection)
        assertEquals(Int.MAX_VALUE, options.reconnectionAttempts)
        assertEquals(1_000L, options.reconnectionDelayMs)
        assertEquals(5_000L, options.reconnectionDelayMaxMs)
        assertEquals(0.5, options.randomizationFactor)
        assertTrue(options.multiplex)
        assertFalse(options.forceNew)
        assertFalse(options.trustAllCerts)
        assertEquals(null, options.httpClient)
        assertEquals(Logger.NoOp, options.logger)
        assertEquals(EventBufferConfig(), options.eventBuffer)
        assertFalse(options.experimentalWebTransport)
    }

    @Test
    fun dslProducesImmutableSnapshot() {
        val options = socketOptions {
            path = "/custom/"
            transports(Transports.WEBSOCKET)
            upgrade = false
            auth { put("token", "abc") }
            extraHeaders { this["X-Test"] = listOf("one", "two") }
            timeoutMs = 30_000
            reconnection = false
            reconnectionAttempts = 3
            reconnectionDelayMs = 2_000
            reconnectionDelayMaxMs = 10_000
            randomizationFactor = 0.25
            multiplex = false
            forceNew = true
            trustAllCerts = true
            logger = object : Logger {
                override fun debug(tag: String, message: String) = Unit
                override fun info(tag: String, message: String) = Unit
                override fun error(tag: String, message: String, throwable: Throwable?) = Unit
            }
        }
        assertEquals("/custom/", options.path)
        assertEquals(listOf(Transports.WEBSOCKET), options.transports)
        assertFalse(options.upgrade)
        assertEquals(buildJsonObject { put("token", "abc") }, options.auth)
        assertEquals(mapOf("X-Test" to listOf("one", "two")), options.extraHeaders)
        assertEquals(30_000L, options.timeoutMs)
        assertFalse(options.reconnection)
        assertEquals(3, options.reconnectionAttempts)
        assertEquals(2_000L, options.reconnectionDelayMs)
        assertEquals(10_000L, options.reconnectionDelayMaxMs)
        assertEquals(0.25, options.randomizationFactor)
        assertFalse(options.multiplex)
        assertTrue(options.forceNew)
        assertTrue(options.trustAllCerts)
        assertNotSame(Logger.NoOp, options.logger)
    }

    @Test
    fun successiveBuildsAreIndependent() {
        val first = socketOptions { auth { put("a", "1") } }
        val second = socketOptions { auth { put("b", "2") } }
        assertEquals(buildJsonObject { put("a", "1") }, first.auth)
        assertEquals(buildJsonObject { put("b", "2") }, second.auth)
    }

    @Test
    fun authSupportsTypedAndNestedValues() {
        val options = socketOptions {
            auth {
                put("token", "jwt")
                put("n", 5)
                put("flag", true)
                putJsonObject("meta") { put("deviceId", 42) }
            }
            auth { put("extra", "later") } // accumulates across calls
        }
        val expected = buildJsonObject {
            put("token", "jwt")
            put("n", 5)
            put("flag", true)
            putJsonObject("meta") { put("deviceId", 42) }
            put("extra", "later")
        }
        assertEquals(expected, options.auth)
    }

    @Test
    fun literalTransportNamesMatchConstants() {
        val options = socketOptions {
            transports("polling", "websocket")
        }
        assertEquals(listOf(Transports.POLLING, Transports.WEBSOCKET), options.transports)
    }

    @Test
    fun filtersUnsupportedTransports() {
        val options = socketOptions {
            transports("foo", Transports.POLLING)
        }
        assertEquals(listOf(Transports.POLLING), options.transports)
    }

    @Test
    fun failsWhenAllTransportsUnsupported() {
        assertFailsWith<IllegalArgumentException> {
            socketOptions { transports("foo") }
        }
    }

    @Test
    fun preservesTransportOrder() {
        val options = socketOptions {
            transports(Transports.WEBSOCKET, Transports.POLLING)
        }
        assertEquals(listOf(Transports.WEBSOCKET, Transports.POLLING), options.transports)
    }

    @Test
    fun logsDroppedTransportsAtDebug() {
        var debugMessage: String? = null
        val options = socketOptions {
            logger = object : Logger {
                override fun debug(tag: String, message: String) {
                    debugMessage = message
                }

                override fun info(tag: String, message: String) = Unit
                override fun error(tag: String, message: String, throwable: Throwable?) = Unit
            }
            transports("foo", Transports.POLLING)
        }
        assertEquals(listOf(Transports.POLLING), options.transports)
        assertEquals("dropped 1 unsupported transport(s)", debugMessage)
    }

    @Test
    fun dropsWebTransportByDefault() {
        val options = socketOptions {
            transports(Transports.WEBSOCKET, Transports.WEBTRANSPORT)
        }
        assertEquals(listOf(Transports.WEBSOCKET), options.transports)
    }

    @Test
    fun keepsWebTransportWhenExperimentalFlagEnabled() {
        val options = socketOptions {
            experimentalWebTransport = true
            transports(Transports.WEBTRANSPORT, Transports.WEBSOCKET)
        }
        assertTrue(options.experimentalWebTransport)
        assertEquals(listOf(Transports.WEBTRANSPORT, Transports.WEBSOCKET), options.transports)
    }
}
