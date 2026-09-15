package dev.adven.sockit

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.EmbeddedEngineIoServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReconnectPauseResumeTest {
    @BeforeTest
    fun setUp() {
        EmbeddedEngineIoServer.configure()
        EmbeddedEngineIoServer.start()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        SocketClientRegistry.resetForTests()
        EmbeddedEngineIoServer.stop()
    }

    @Test
    fun pauseReconnectBlocksBackoffUntilResume() = runBlocking {
        val url = "http://localhost:${EmbeddedEngineIoServer.port}"
        val client = SocketClient.connect(
            url,
            socketOptions {
                transports(Transports.POLLING)
                upgrade = false
                reconnection = true
                reconnectionDelayMs = 800
                reconnectionDelayMaxMs = 800
            },
        )
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())

        val serverPort = EmbeddedEngineIoServer.port
        EmbeddedEngineIoServer.stop()
        withTimeout(5.seconds) {
            socket.connectionState.first { it is ConnectionState.Reconnecting }
        }

        client.pauseReconnect()
        delay(1_500.milliseconds)
        assertFalse(socket.isConnected())

        EmbeddedEngineIoServer.start(serverPort)
        delay(1_000.milliseconds)
        assertFalse(socket.isConnected(), "paused client must not reconnect until resume")

        client.resumeReconnect()
        withTimeout(10.seconds) {
            socket.connectionState.first { it is ConnectionState.Connected }
        }
        assertTrue(socket.isConnected())
        client.closeAwait()
    }

    @Test
    fun pauseReconnectWhileConnectedDoesNotDropTransport() = runBlocking {
        val url = "http://localhost:${EmbeddedEngineIoServer.port}"
        val client = SocketClient.connect(
            url,
            socketOptions {
                transports(Transports.POLLING)
                upgrade = false
                reconnection = true
            },
        )
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())

        client.pauseReconnect()
        delay(200.milliseconds)
        assertTrue(socket.isConnected(), "pause must not tear down an active connection")

        socket.emitAwait("echo", "while-paused")
        client.closeAwait()
    }
}
