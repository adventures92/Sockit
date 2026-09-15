package dev.adven.sockit.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedEngineIoServerTest {
    @BeforeTest
    fun setUp() {
        EmbeddedEngineIoServer.configure(
            pingIntervalMs = 100,
            pingTimeoutMs = 300,
            respondToPings = false,
        )
        EmbeddedEngineIoServer.start()
    }

    @AfterTest
    fun tearDown() {
        EmbeddedEngineIoServer.stop()
    }

    @Test
    fun handshakeExposesConfiguredPingTimers() = runBlocking {
        val client = HttpClient(CIO)
        client.use {
            val body = it.get("http://localhost:${EmbeddedEngineIoServer.port}/socket.io/?EIO=4&transport=polling")
                .bodyAsText()
            assertTrue(body.contains("\"pingInterval\":100"))
            assertTrue(body.contains("\"pingTimeout\":300"))
        }
    }

    @Test
    fun suppressesPongResponseWhenConfigured() = runBlocking {
        val client = HttpClient(CIO)
        client.use {
            val open = it.get("http://localhost:${EmbeddedEngineIoServer.port}/socket.io/?EIO=4&transport=polling")
                .bodyAsText()
            val sid = """"sid":"([^"]+)"""".toRegex().find(open)!!.groupValues[1]
            val post = it.post(
                "http://localhost:${EmbeddedEngineIoServer.port}/socket.io/?EIO=4&transport=polling&sid=$sid",
            ) {
                setBody("2")
            }.bodyAsText()
            assertEquals("", post)
        }
    }
}
