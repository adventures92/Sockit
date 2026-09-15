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

class PollingTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var httpClient: io.ktor.client.HttpClient

    @BeforeTest
    fun setUp() {
        SocketTestServer.start()
        SocketTestServer.awaitReady()
        httpClient = createPlatformHttpClient()
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        scope.cancel()
        SocketTestServer.stop()
    }

    @Test
    fun handshakeReceivesOpenPacket() = runBlocking {
        val openPacket = CompletableDeferred<EnginePacket.Open>()
        val transport = createPollingTransport()

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

        val open = withTimeout(10.seconds) { openPacket.await() }
        assertTrue(open.sid.isNotEmpty())
        assertTrue(open.upgrades.contains("websocket"))

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
            val transport = createPollingTransport()

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
    fun postFailureEmitsError() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val errorReceived = CompletableDeferred<Any>()
        val transport = createPollingTransport()

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

    /**
     * Uses the embedded Engine.IO server — Node long-poll can block until timeout and flake
     * [EVENT_POLL] ordering assertions.
     */
    @Test
    fun pauseWaitsForInFlightPoll() = runBlocking {
        SocketTestServer.stop()
        SocketTestServer.startEmbedded(pingIntervalMs = 60_000, pingTimeoutMs = 30_000)
        try {
            pauseWaitsForInFlightPollBody()
        } finally {
            SocketTestServer.stop()
        }
    }

    private suspend fun pauseWaitsForInFlightPollBody() {
        val opened = CompletableDeferred<Unit>()
        val pauseCompleted = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var pollCount = 0
        val transport = createPollingTransport()

        transport.events.once(
            Transport.EVENT_OPEN,
            EventBus.Listener {
                opened.complete(Unit)
            },
        )

        transport.events.on(
            Transport.EVENT_POLL,
            EventBus.Listener {
                pollCount++
                if (pollCount == 2) {
                    transport.pause {
                        order.add("paused")
                        pauseCompleted.complete(Unit)
                    }
                }
            },
        )

        transport.events.on(
            Transport.EVENT_POLL_COMPLETE,
            EventBus.Listener {
                order.add("pollComplete")
            },
        )

        transport.open()
        withTimeout(15.seconds) { opened.await() }
        withTimeout(15.seconds) { pauseCompleted.await() }

        val pausedIndex = order.indexOf("paused")
        assertTrue(pausedIndex > 0, "expected paused event")
        assertTrue(
            order.subList(0, pausedIndex).contains("pollComplete"),
            "pause must wait for in-flight poll to complete",
        )
    }

    /**
     * After [PollingTransport.pause], [PollingTransport.resume] must return the transport to OPEN
     * and restart the poll loop (a fresh EVENT_POLL). Uses the embedded server for deterministic
     * poll timing. Guards the post-pong upgrade-probe-failure recovery path.
     */
    @Test
    fun resumeAfterPauseRestartsPolling() = runBlocking {
        SocketTestServer.stop()
        SocketTestServer.startEmbedded(pingIntervalMs = 60_000, pingTimeoutMs = 30_000)
        try {
            val opened = CompletableDeferred<Unit>()
            val pauseCompleted = CompletableDeferred<Unit>()
            val polledAfterResume = CompletableDeferred<Unit>()
            val transport = createPollingTransport()
            var resumed = false

            transport.events.once(
                Transport.EVENT_OPEN,
            ) { opened.complete(Unit) }
            transport.events.on(
                Transport.EVENT_POLL,
            ) {
                if (resumed && !polledAfterResume.isCompleted) {
                    polledAfterResume.complete(Unit)
                }
            }

            transport.open()
            withTimeout(15.seconds) { opened.await() }

            transport.pause { pauseCompleted.complete(Unit) }
            withTimeout(15.seconds) { pauseCompleted.await() }

            resumed = true
            transport.resume()
            // resume() must restart the poll loop → a fresh EVENT_POLL fires.
            withTimeout(15.seconds) { polledAfterResume.await() }

            transport.close()
        } finally {
            SocketTestServer.stop()
        }
        Unit
    }

    @Test
    fun pauseWaitsForInFlightWrite() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val pauseCompleted = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val transport = createPollingTransport()

        transport.events.once(
            Transport.EVENT_OPEN,
            EventBus.Listener {
                opened.complete(Unit)
                transport.send(listOf(OutboundEngineMessage(EnginePacket.Ping())))
                transport.pause {
                    order.add("paused")
                    pauseCompleted.complete(Unit)
                }
            },
        )

        transport.events.on(
            Transport.EVENT_DRAIN,
            EventBus.Listener {
                order.add("drain")
            },
        )

        transport.open()
        withTimeout(10.seconds) { opened.await() }
        withTimeout(10.seconds) { pauseCompleted.await() }

        assertEquals(listOf("drain", "paused"), order)
        Unit
    }

    @Test
    fun closeSendsClosePacket() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val transport = createPollingTransport()

        transport.events.once(
            Transport.EVENT_OPEN,
            EventBus.Listener {
                opened.complete(Unit)
            },
        )

        transport.events.once(
            Transport.EVENT_CLOSE,
            EventBus.Listener {
                closed.complete(Unit)
            },
        )

        transport.open()
        withTimeout(10.seconds) { opened.await() }

        transport.close()
        withTimeout(10.seconds) { closed.await() }

        if (SocketTestServer.isEmbedded) {
            assertTrue(
                SocketTestServer.postedBodies().any { body ->
                    body == "1" || body.contains('\u001e') && body.split('\u001e').contains("1")
                },
                "close() must POST Engine.IO Close packet (1)",
            )
        }
        Unit
    }

    @Test
    fun uriIncludesEngineIoQueryParams() {
        val transport = createPollingTransport()
        val uri = transport.uri()
        assertTrue(uri.contains("EIO=4"))
        assertTrue(uri.contains("transport=polling"))
        assertTrue(uri.startsWith("http://localhost:${SocketTestServer.port}/socket.io/"))
    }

    @Test
    fun uriIncludesTimestampWhenEnabled() {
        val options = TransportOptions(
            hostname = "localhost",
            port = SocketTestServer.port,
            path = "/socket.io/",
            query = mapOf("EIO" to "4", "transport" to "polling"),
            timestampRequests = true,
            timestampParam = "t",
        )
        val transport = PollingTransport(
            options = options,
            httpClient = httpClient,
            log = SocketLog(Logger.NoOp),
            scope = scope,
        )
        val uri = transport.uri()
        assertTrue(uri.contains("t="), "Expected cache-bust param t= in $uri")
        val timestamp = uri.substringAfter("t=").substringBefore("&").substringBefore("?")
        assertTrue(timestamp.matches(Regex("[0-9a-z]+")), "Expected base-36 timestamp, got $timestamp")
    }

    private fun createPollingTransport(sid: String? = null): PollingTransport {
        val options = buildTransportOptions(
            socketOptions = dev.adven.sockit.api.socketOptions { },
            hostname = "localhost",
            port = SocketTestServer.port,
            secure = false,
            transportName = PollingTransport.NAME,
            sid = sid,
        )
        return PollingTransport(
            options = options,
            httpClient = httpClient,
            log = SocketLog(Logger.NoOp),
            scope = scope,
        )
    }
}
