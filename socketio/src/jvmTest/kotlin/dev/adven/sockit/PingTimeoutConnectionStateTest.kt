package dev.adven.sockit

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.SocketTestServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `ConnectionState.Failed` is documented as terminal (guide/connection-state.md,
 * guide/reconnection.md), with ping timeout listed as one of its causes. These pin down both
 * halves of that contract at the `SocketClient` level, which
 * [dev.adven.sockit.engineio.EngineConnectionHeartbeatTest] does not cover — it drives
 * [dev.adven.sockit.engineio.EngineConnection] directly and asserts only the `errors` flow.
 *
 * A server configured with `sendServerPings = false` goes silent after the handshake, which is
 * what arms the client's ping-timeout watchdog.
 */
class PingTimeoutConnectionStateTest {
    private val serverUrl: String
        get() = "http://localhost:${SocketTestServer.port}"

    // Goes through SocketTestServer rather than driving EmbeddedEngineIoServer directly:
    // that server is a singleton whose start() silently ignores the requested port when one is
    // already running, so a second owner can strand another suite on a dead port for 15s.
    @BeforeTest
    fun setUp() {
        SocketTestServer.startEmbedded(
            pingIntervalMs = 100,
            pingTimeoutMs = 300,
            sendServerPings = false,
        )
    }

    @AfterTest
    fun tearDown() = runBlocking {
        SocketClientRegistry.resetForTests()
        SocketTestServer.stop()
    }

    @Test
    fun pingTimeoutWithoutReconnectionIsTerminallyFailed() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        val pingTimeout = async { client.errors.first { it is SocketError.PingTimeout } }
        socket.openAwait()

        val error = withTimeout(5.seconds) { pingTimeout.await() }
        // Nothing will retry, so the drop is terminal. Read the state well after the engine close
        // that follows the timeout: that close must carry the error into the state rather than
        // resetting both levels to a plain Disconnected that says nothing about what went wrong.
        delay(500.milliseconds)
        assertEquals(ConnectionState.Failed(error), socket.connectionState.value)
        assertEquals(ConnectionState.Failed(error), client.connectionState.value)

        client.close()
    }

    @Test
    fun pingTimeoutWithReconnectionNeverPublishesFailed() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = true
                // Long enough that the first Reconnecting is still in force when we assert.
                reconnectionDelayMs = 5_000
                reconnectionDelayMaxMs = 5_000
            },
        )
        val socket = client.namespace()

        val seen = mutableListOf<ConnectionState>()
        val observer = launch { client.connectionState.collect { seen += it } }

        val pingTimeout = async { client.errors.first { it is SocketError.PingTimeout } }
        socket.openAwait()
        withTimeout(5.seconds) { pingTimeout.await() }
        delay(500.milliseconds)

        // Failed is terminal. A drop the library is about to retry is Reconnecting, and publishing
        // Failed on the way there would make every transient timeout look permanent to a consumer
        // that reacts to Failed by telling the user to fix their credentials.
        assertTrue(
            seen.none { it is ConnectionState.Failed },
            "Failed must not be published while reconnecting, saw: $seen",
        )
        assertTrue(
            client.connectionState.value is ConnectionState.Reconnecting,
            "expected Reconnecting, was ${client.connectionState.value}",
        )

        observer.cancel()
        client.close()
    }
}
