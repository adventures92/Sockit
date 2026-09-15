package dev.adven.sockit.engineio

import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.transport.EmbeddedEngineIoServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Engine.IO v4 makes the SERVER responsible for heartbeat timing — it sends PING, the client only
 * replies PONG and watches for the next PING within pingInterval + pingTimeout. A v3-style
 * client-initiated ping loop caused a real production drop (server closed the transport on
 * receiving a ping it never asked for, once per pingInterval — see
 * docs/superpowers/specs/2026-08-18-sockit-reconnect-drop-design.md at the workspace root). These
 * tests pin the corrected direction down.
 */
class EngineConnectionHeartbeatTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        EmbeddedEngineIoServer.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        EmbeddedEngineIoServer.stop()
    }

    @Test
    fun clientNeverSendsAnUnsolicitedPing() = runBlocking {
        EmbeddedEngineIoServer.configure(pingIntervalMs = 100, pingTimeoutMs = 5_000, sendServerPings = false)
        val engine = openEngine()
        val opened = CompletableDeferred<Unit>()
        engine.events.once(EngineConnection.EVENT_OPEN) { opened.complete(Unit) }

        engine.open()
        withTimeout(5.seconds) { opened.await() }
        delay(500.milliseconds) // several multiples of pingIntervalMs=100, well under pingTimeoutMs=5000

        assertEquals(0, EmbeddedEngineIoServer.clientPingCount())
        engine.close()
    }

    @Test
    fun pingTimeoutEmitsErrorAndClosesWhenServerNeverPings() = runBlocking {
        EmbeddedEngineIoServer.configure(pingIntervalMs = 100, pingTimeoutMs = 300, sendServerPings = false)
        val engine = openEngine()
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<String>()
        val errorFromFlow = async { engine.errors.first() }

        engine.events.once(
            EngineConnection.EVENT_OPEN,
        ) {
            opened.complete(Unit)
        }
        engine.events.once(
            EngineConnection.EVENT_CLOSE,
        ) { args ->
            closed.complete(args.first() as String)
        }

        engine.open()
        withTimeout(5.seconds) { opened.await() }

        val closeReason = withTimeout(3.seconds) { closed.await() }
        assertTrue(closeReason.contains("ping timeout"))

        val error = withTimeout(1.seconds) { errorFromFlow.await() }
        assertTrue(error is SocketError.PingTimeout)
    }

    @Test
    fun clientRespondsToServerPingsAndStaysConnected() = runBlocking {
        EmbeddedEngineIoServer.configure(pingIntervalMs = 100, pingTimeoutMs = 300, sendServerPings = true)
        val engine = openEngine()
        val opened = CompletableDeferred<Unit>()
        var closed = false
        engine.events.once(EngineConnection.EVENT_OPEN) { opened.complete(Unit) }
        engine.events.on(EngineConnection.EVENT_CLOSE) { closed = true }

        engine.open()
        withTimeout(5.seconds) { opened.await() }
        delay(500.milliseconds) // several server ping cycles at pingIntervalMs=100

        assertFalse(closed)
        engine.close()
    }

    private fun openEngine(): EngineConnection = EngineConnection(
        url = "http://localhost:${EmbeddedEngineIoServer.port}",
        options = socketOptions {
            reconnection = false
            transports(Transports.WEBSOCKET)
            upgrade = false
        },
        scope = scope,
    )
}
