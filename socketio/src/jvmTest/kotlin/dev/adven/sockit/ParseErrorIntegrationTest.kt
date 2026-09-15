package dev.adven.sockit

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.EmbeddedEngineIoServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ParseErrorIntegrationTest {
    @BeforeTest
    fun startServer() {
        EmbeddedEngineIoServer.configure()
        EmbeddedEngineIoServer.start()
    }

    @AfterTest
    fun stopServer() = runBlocking {
        SocketClientRegistry.resetForTests()
        EmbeddedEngineIoServer.stop()
    }

    @Test
    fun malformedInboundPacketEmitsParseErrorWithoutDisconnect() = runBlocking {
        val url = "http://localhost:${EmbeddedEngineIoServer.port}"
        val client = SocketClient.connect(
            url,
            socketOptions {
                transports(Transports.POLLING)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())

        val errorDeferred = async {
            withTimeout(15.seconds) {
                socket.errors.first { it is SocketError.ParseError }
            }
        }
        delay(100.milliseconds)
        EmbeddedEngineIoServer.queueNextPollPayload("not-valid-engine-io")
        val error = errorDeferred.await()
        assertEquals(SocketError.ParseError("not-valid-engine-io"), error)
        delay(300.milliseconds)

        assertTrue(socket.isConnected(), "ParseError must be non-terminal")
        assertEquals(ConnectionState.Connected, socket.connectionState.value)

        client.close()
    }
}
