package dev.adven.sockit

import app.cash.turbine.test
import dev.adven.sockit.api.EventBufferOverflow
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketPayload
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.SocketTestServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun SocketPayload?.asText(): String? = when (this) {
    is SocketPayload.Text -> value
    is SocketPayload.Json -> element.jsonPrimitive.content
    else -> null
}

class EventBufferOverflowIntegrationTest {
    private val serverUrl: String
        get() = "http://localhost:${SocketTestServer.port}"

    @BeforeTest
    fun ensureServerRunning() {
        when {
            SocketTestServer.port == 0 -> {
                SocketTestServer.start()
                SocketTestServer.awaitReady()
            }
            !SocketTestServer.isHealthy() -> SocketTestServer.restartNode()
            else -> Unit
        }
    }

    @AfterTest
    fun repairAfterTest() = runBlocking {
        SocketClientRegistry.resetForTests()
        if (SocketTestServer.port != 0 && !SocketTestServer.isHealthy()) {
            SocketTestServer.restartNode()
        }
    }

    @Test
    fun customEventBufferOptionsDeliverEvents() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.POLLING)
                upgrade = false
                reconnection = false
                eventBufferCapacity = 8
                eventBufferOverflow = EventBufferOverflow.DROP_LATEST
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        socket.events("echoBack").test {
            socket.emit("echo", "ping")
            assertEquals("ping", awaitItem().args.firstOrNull().asText())
            cancelAndIgnoreRemainingEvents()
        }
        client.close()
    }
}
