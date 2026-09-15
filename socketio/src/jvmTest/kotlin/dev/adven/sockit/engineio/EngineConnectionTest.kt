package dev.adven.sockit.engineio

import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.internal.EventBus
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import dev.adven.sockit.protocol.SocketPacket
import dev.adven.sockit.transport.EmbeddedEngineIoServer
import dev.adven.sockit.transport.SocketTestServer
import dev.adven.sockit.transport.WebSocketTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

class EngineConnectionTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        SocketTestServer.start()
        SocketTestServer.awaitReady()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        SocketTestServer.stop()
    }

    @Test
    fun handshakeReceivesOpenAndSid() = runBlocking {
        val engine = createEngine()
        val handshake = CompletableDeferred<EnginePacket.Open>()
        val opened = CompletableDeferred<Unit>()

        engine.events.once(
            EngineConnection.EVENT_HANDSHAKE,
            EventBus.Listener { args ->
                handshake.complete(args.first() as EnginePacket.Open)
            },
        )
        engine.events.once(
            EngineConnection.EVENT_OPEN,
            EventBus.Listener {
                opened.complete(Unit)
            },
        )

        engine.open()
        val open = withTimeout(10.seconds) { handshake.await() }
        withTimeout(10.seconds) { opened.await() }

        assertTrue(open.sid.isNotEmpty())
        assertTrue(open.upgrades.contains("websocket"))

        engine.close()
        delay(300.milliseconds)
        Unit
    }

    /**
     * Client-initiated ping/pong uses the embedded Engine.IO server — Node EIO v4 rejects
     * client-initiated pings ("invalid heartbeat direction").
     */
    @Test
    fun pingPongRoundTrip() = runBlocking {
        SocketTestServer.stop()
        SocketTestServer.startForPingPongTest()
        try {
            // Isolate client ping/pong from websocket upgrade — flush is paused while probing.
            val engine = createEngine(
                socketOptions {
                    transports(Transports.POLLING)
                    upgrade = false
                },
            )
            val opened = CompletableDeferred<Unit>()
            val pongReceived = CompletableDeferred<Unit>()

            engine.events.on(
                EngineConnection.EVENT_PACKET,
                EventBus.Listener { args ->
                    val packet = args.firstOrNull() as? EnginePacket.Pong ?: return@Listener
                    if (packet.payload == null && !pongReceived.isCompleted) {
                        pongReceived.complete(Unit)
                    }
                },
            )
            engine.events.once(
                EngineConnection.EVENT_OPEN,
                EventBus.Listener {
                    opened.complete(Unit)
                    engine.send(EnginePacket.Ping())
                },
            )

            engine.open()
            withTimeout(10.seconds) { opened.await() }
            withTimeout(10.seconds) { pongReceived.await() }
            engine.close()
        } finally {
            SocketTestServer.stop()
        }
        Unit
    }

    @Test
    fun upgradesFromPollingToWebSocket() = runBlocking {
        val engine = createEngine(
            socketOptions {
                transports(Transports.POLLING, Transports.WEBSOCKET)
                upgrade = true
            },
        )
        val upgraded = CompletableDeferred<String>()
        val opened = CompletableDeferred<Unit>()

        engine.events.once(
            EngineConnection.EVENT_OPEN,
            EventBus.Listener {
                opened.complete(Unit)
            },
        )
        engine.events.once(
            EngineConnection.EVENT_UPGRADE,
            EventBus.Listener { args ->
                val transport = args.first() as dev.adven.sockit.transport.Transport
                upgraded.complete(transport.name)
            },
        )

        engine.open()
        withTimeout(10.seconds) { opened.await() }
        assertEquals(WebSocketTransport.NAME, withTimeout(15.seconds) { upgraded.await() })

        engine.close()
        delay(300.milliseconds)
        Unit
    }

    /**
     * Uses the embedded Engine.IO server — stopping Node mid-test can return a parse error on
     * the polling response body instead of a transport failure.
     */
    @Test
    fun transportErrorEmitsSocketError() = runBlocking {
        SocketTestServer.stop()
        SocketTestServer.startEmbedded(pingIntervalMs = 60_000, pingTimeoutMs = 30_000)
        try {
            val engine = createEngine(
                socketOptions {
                    reconnection = false
                    transports(Transports.POLLING)
                    upgrade = false
                },
            )
            val opened = CompletableDeferred<Unit>()
            val errorFromFlow = async { engine.errors.first() }

            engine.events.once(
                EngineConnection.EVENT_OPEN,
                EventBus.Listener {
                    opened.complete(Unit)
                },
            )

            engine.open()
            withTimeout(15.seconds) { opened.await() }

            SocketTestServer.stop()
            delay(500.milliseconds)

            engine.send(EnginePacket.Ping())

            val error = withTimeout(15.seconds) { errorFromFlow.await() }
            assertTrue(
                error is SocketError.SendFailed ||
                    error is SocketError.TransportClosed,
            )
        } finally {
            if (SocketTestServer.port != 0) {
                SocketTestServer.stop()
            }
        }
        Unit
    }

    /**
     * Uses the embedded server so [maxPayload] can be forced small and deterministic.
     */
    @Test
    fun oversizedPollingMessageIsDroppedNotRetriedForever() = runBlocking {
        SocketTestServer.stop()
        SocketTestServer.startEmbedded(pingIntervalMs = 60_000, pingTimeoutMs = 30_000)
        EmbeddedEngineIoServer.configure(maxPayload = 40)
        try {
            val engine = createEngine(
                socketOptions {
                    reconnection = false
                    transports(Transports.POLLING)
                    upgrade = false
                },
            )
            val opened = CompletableDeferred<Unit>()
            var closed = false
            val errorFromFlow = async { engine.errors.first() }

            engine.events.once(EngineConnection.EVENT_OPEN, EventBus.Listener { opened.complete(Unit) })
            engine.events.on(EngineConnection.EVENT_CLOSE, EventBus.Listener { closed = true })

            engine.open()
            withTimeout(15.seconds) { opened.await() }

            // Far exceeds the 40-byte maxPayload advertised above.
            val oversized = SocketPacket.Event("/", """["echo","${"x".repeat(200)}"]""")
            engine.send(EnginePacket.Message(oversized))

            val error = withTimeout(10.seconds) { errorFromFlow.await() }
            assertTrue(error is SocketError.SendFailed, "expected the oversized message to be dropped with an error, got $error")

            // The engine must still be usable afterwards — a small message doesn't get stuck
            // behind the dropped one, and the connection isn't torn down.
            val small = SocketPacket.Event("/", """["echo","ok"]""")
            engine.send(EnginePacket.Message(small))
            delay(500.milliseconds)
            assertFalse(closed, "engine must remain open after dropping only the oversized message")

            engine.close()
        } finally {
            EmbeddedEngineIoServer.resetConfig()
            if (SocketTestServer.port != 0) {
                SocketTestServer.stop()
            }
        }
        Unit
    }

    /**
     * Regression for a batching edge case: [dev.adven.sockit.socketio.NamespaceSocketImpl] can
     * flush several pre-connect messages via a single [EngineConnection.sendMessages] call, which
     * lands multiple oversized messages adjacent at the head of `writeBuffer` inside the very
     * same `flush()` window (one `addAll` + one `flush`, no drain in between). Uses
     * `sendMessages` directly (rather than two back-to-back [EngineConnection.send] calls) because
     * each `send` schedules its own single-message `addAll`+`flush` task on the serial work queue,
     * which completes before the next `send`'s task starts — so two separate `send` calls never
     * actually land both messages in the same flush, and wouldn't exercise this batching bug.
     */
    @Test
    fun multipleAdjacentOversizedPollingMessagesAreAllDroppedInOneFlush() = runBlocking {
        SocketTestServer.stop()
        SocketTestServer.startEmbedded(pingIntervalMs = 60_000, pingTimeoutMs = 30_000)
        EmbeddedEngineIoServer.configure(maxPayload = 40)
        try {
            val engine = createEngine(
                socketOptions {
                    reconnection = false
                    transports(Transports.POLLING)
                    upgrade = false
                },
            )
            val opened = CompletableDeferred<Unit>()
            var closed = false
            val errorsFromFlow = async { engine.errors.take(2).toList() }

            engine.events.once(EngineConnection.EVENT_OPEN, EventBus.Listener { opened.complete(Unit) })
            engine.events.on(EngineConnection.EVENT_CLOSE, EventBus.Listener { closed = true })

            engine.open()
            withTimeout(15.seconds) { opened.await() }

            // Both far exceed the 40-byte maxPayload advertised above. Queuing them together in a
            // single sendMessages call (one addAll + one flush) forces them adjacent at the head
            // of writeBuffer in the same flush window, mirroring NamespaceSocketImpl's batched
            // pre-connect flush.
            val oversized1 = SocketPacket.Event("/", """["echo","${"x".repeat(200)}"]""")
            val oversized2 = SocketPacket.Event("/", """["echo","${"y".repeat(200)}"]""")
            engine.sendMessages(
                listOf(
                    OutboundEngineMessage(EnginePacket.Message(oversized1)),
                    OutboundEngineMessage(EnginePacket.Message(oversized2)),
                ),
            )

            val errors = withTimeout(10.seconds) { errorsFromFlow.await() }
            assertEquals(
                2,
                errors.count { it is SocketError.SendFailed },
                "expected both adjacent oversized messages to be dropped independently, got $errors",
            )

            // The engine must still be usable afterwards — neither dropped message left the
            // connection stuck or torn down.
            val small = SocketPacket.Event("/", """["echo","ok"]""")
            engine.send(EnginePacket.Message(small))
            delay(500.milliseconds)
            assertFalse(closed, "engine must remain open after dropping only the oversized messages")

            engine.close()
        } finally {
            EmbeddedEngineIoServer.resetConfig()
            if (SocketTestServer.port != 0) {
                SocketTestServer.stop()
            }
        }
        Unit
    }

    @Test
    fun pollingOnlySkipsUpgrade() = runBlocking {
        val engine = createEngine(
            socketOptions {
                transports(Transports.POLLING)
                upgrade = true
            },
        )
        val opened = CompletableDeferred<Unit>()
        var upgradeCount = 0

        engine.events.once(
            EngineConnection.EVENT_OPEN,
            EventBus.Listener {
                opened.complete(Unit)
            },
        )
        engine.events.on(
            EngineConnection.EVENT_UPGRADE,
            EventBus.Listener {
                upgradeCount++
            },
        )

        engine.open()
        withTimeout(10.seconds) { opened.await() }
        delay(500.milliseconds)

        assertEquals(0, upgradeCount)
        engine.close()
        Unit
    }

    private fun createEngine(options: dev.adven.sockit.api.SocketOptions = socketOptions { }): EngineConnection = EngineConnection(
        url = "http://localhost:${SocketTestServer.port}",
        options = options,
        scope = scope,
    )
}
