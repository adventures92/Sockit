package dev.adven.sockit.engineio

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.transport.SocketTestServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Upgrade race chaos (architecture §11, gap #1) — close while probe/upgrade may be active, then reopen.
 *
 * Exercises the full Socket.IO stack (ConnectionManager → EngineConnection) so probe/upgrade
 * teardown and reopen do not crash — mirrors kmp-socketio ut-case-analysis.
 */
class UpgradeChaosTest {
    @BeforeTest
    fun setUp() {
        SocketTestServer.start()
        SocketTestServer.awaitReady()
    }

    @AfterTest
    fun tearDown() {
        SocketTestServer.stop()
    }

    @Test
    fun closeDuringUpgradeThenReopen() = runBlocking {
        val client = SocketClient.connect(
            "http://localhost:${SocketTestServer.port}",
            socketOptions {
                transports(Transports.POLLING, Transports.WEBSOCKET)
                upgrade = true
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.open()
        // Close while handshake / WS probe may still be in flight (gap #1).
        withTimeout(10.seconds) {
            socket.connectionState.first {
                it is ConnectionState.Connecting || it is ConnectionState.Connected
            }
        }
        socket.close()
        withTimeout(15.seconds) {
            socket.openAwait()
        }
        assertTrue(socket.isConnected())
        client.close()
    }
}
