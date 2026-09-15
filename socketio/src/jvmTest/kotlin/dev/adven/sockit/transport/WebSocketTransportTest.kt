package dev.adven.sockit.transport

import dev.adven.sockit.api.Logger
import dev.adven.sockit.internal.EventBus
import dev.adven.sockit.internal.logging.SocketLog
import dev.adven.sockit.platform.createPlatformHttpClient
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WebSocketTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var httpClient: io.ktor.client.HttpClient

    @BeforeTest
    fun setUp() {
        SocketTestServer.start()
        httpClient = createPlatformHttpClient()
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        scope.cancel()
        SocketTestServer.stop()
    }

    @Test
    fun websocketOnlyHandshakeReachesOpen() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val openPacket = CompletableDeferred<EnginePacket.Open>()
        val transport = createWebSocketTransport()

        transport.events.once(
            Transport.EVENT_OPEN,
            EventBus.Listener {
                opened.complete(Unit)
            },
        )

        transport.events.once(
            Transport.EVENT_PACKET,
            EventBus.Listener { args ->
                val packet = args.firstOrNull() as? EnginePacket ?: return@Listener
                if (packet is EnginePacket.Open) {
                    openPacket.complete(packet)
                }
            },
        )

        transport.open()

        withTimeout(10.seconds) { opened.await() }
        val open = withTimeout(10.seconds) { openPacket.await() }
        assertTrue(open.sid.isNotEmpty())

        transport.close()
        delay(200.milliseconds)
        Unit
    }

    @Test
    fun pingPongRoundTrip() = runBlocking {
        // Node EIO v4 rejects client-initiated pings; embedded server validates wire ping/pong.
        SocketTestServer.stop()
        SocketTestServer.startForPingPongTest()
        try {
            val pongReceived = CompletableDeferred<Unit>()
            val transport = createWebSocketTransport()

            transport.events.once(
                Transport.EVENT_OPEN,
                EventBus.Listener {
                    transport.send(listOf(OutboundEngineMessage(EnginePacket.Ping())))
                },
            )

            transport.events.on(
                Transport.EVENT_PACKET,
                EventBus.Listener { args ->
                    if (args.firstOrNull() is EnginePacket.Pong) {
                        if (!pongReceived.isCompleted) {
                            pongReceived.complete(Unit)
                        }
                    }
                },
            )

            transport.open()
            withTimeout(10.seconds) { pongReceived.await() }
            transport.close()
        } finally {
            SocketTestServer.stop()
        }
        Unit
    }

    @Test
    fun sendFailureEmitsError() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val errorReceived = CompletableDeferred<Any>()
        val transport = createWebSocketTransport()

        transport.events.once(
            Transport.EVENT_OPEN,
            EventBus.Listener {
                opened.complete(Unit)
            },
        )

        transport.events.once(
            Transport.EVENT_ERROR,
            EventBus.Listener { args ->
                if (!errorReceived.isCompleted) {
                    errorReceived.complete(args.first())
                }
            },
        )

        transport.open()
        withTimeout(10.seconds) { opened.await() }

        SocketTestServer.stop()
        delay(300.milliseconds)

        transport.send(listOf(OutboundEngineMessage(EnginePacket.Ping())))

        val error = withTimeout(10.seconds) { errorReceived.await() }
        assertTrue(error is String || error is Throwable)
        Unit
    }

    @Test
    fun probeTransportEmitsZeroDrainCount() = runBlocking {
        val drained = CompletableDeferred<Int>()
        val transport = createWebSocketTransport(isProbe = true)

        transport.events.once(
            Transport.EVENT_OPEN,
            EventBus.Listener {
                transport.send(listOf(OutboundEngineMessage(EnginePacket.Ping("probe"))))
            },
        )

        transport.events.once(
            Transport.EVENT_DRAIN,
            EventBus.Listener { args ->
                val count = args.firstOrNull() as? Int ?: return@Listener
                drained.complete(count)
            },
        )

        transport.open()
        assertEquals(0, withTimeout(10.seconds) { drained.await() })
        transport.close()
        Unit
    }

    @Test
    fun probeTransportEmitsOneDrainCountForUpgradePacket() = runBlocking {
        val drained = CompletableDeferred<Int>()
        val transport = createWebSocketTransport(isProbe = true)

        transport.events.once(
            Transport.EVENT_OPEN,
            EventBus.Listener {
                transport.send(listOf(OutboundEngineMessage(EnginePacket.Upgrade)))
            },
        )

        transport.events.once(
            Transport.EVENT_DRAIN,
            EventBus.Listener { args ->
                val count = args.firstOrNull() as? Int ?: return@Listener
                drained.complete(count)
            },
        )

        transport.open()
        assertEquals(1, withTimeout(10.seconds) { drained.await() })
        transport.close()
        Unit
    }

    @Test
    fun uriUsesWebSocketScheme() {
        val transport = createWebSocketTransport()
        val uri = transport.uri()
        assertTrue(uri.startsWith("ws://localhost:${SocketTestServer.port}/socket.io/"))
        assertTrue(uri.contains("transport=websocket"))
        assertTrue(uri.contains("EIO=4"))
    }

    private fun createWebSocketTransport(isProbe: Boolean = false): WebSocketTransport {
        val options = buildTransportOptions(
            socketOptions = dev.adven.sockit.api.socketOptions { },
            hostname = "localhost",
            port = SocketTestServer.port,
            secure = false,
            transportName = WebSocketTransport.NAME,
        )
        return WebSocketTransport(
            options = options,
            httpClient = httpClient,
            log = SocketLog(Logger.NoOp),
            scope = scope,
            isProbe = isProbe,
        )
    }
}
