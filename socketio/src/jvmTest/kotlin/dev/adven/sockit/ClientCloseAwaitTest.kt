package dev.adven.sockit

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.SocketTestServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ClientCloseAwaitTest {
    private val serverUrl: String
        get() = "http://localhost:${SocketTestServer.port}"

    @BeforeTest
    fun startServer() {
        SocketTestServer.start()
        SocketTestServer.awaitReady()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        SocketClientRegistry.resetForTests()
        SocketTestServer.stop()
    }

    @Test
    fun closeAwaitCompletesBeforeFurtherConnectionStateEmissions() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.POLLING)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(client.isConnected())

        var emissionCount = 0
        val collector = launch {
            client.connectionState.collect {
                emissionCount++
            }
        }

        client.closeAwait()
        val countAfterClose = emissionCount
        delay(500.milliseconds)
        assertEquals(countAfterClose, emissionCount, "no connectionState emissions after closeAwait")
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
        assertTrue(client.isDisconnected())

        collector.cancel()
    }
}
