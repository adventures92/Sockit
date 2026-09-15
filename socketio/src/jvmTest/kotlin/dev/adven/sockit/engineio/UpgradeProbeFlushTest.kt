package dev.adven.sockit.engineio

import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.internal.EventBus
import dev.adven.sockit.internal.logging.SocketLog
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import dev.adven.sockit.protocol.SocketPacket
import dev.adven.sockit.transport.PollingTransport
import dev.adven.sockit.transport.Transport
import dev.adven.sockit.transport.TransportFactory
import dev.adven.sockit.transport.TransportOptions
import dev.adven.sockit.transport.TransportState
import dev.adven.sockit.transport.WebSocketTransport
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic, network-free regression for the WebSocket upgrade-probe CONNECT-strand bug:
 * a packet queued while the engine is probing an upgrade must still reach the polling transport,
 * both while the probe is pending and after it fails — never stranded until the next heartbeat.
 */
class UpgradeProbeFlushTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** Probe opens and stays open (never pongs, never closes) → phase pinned at PROBING. */
    @Test
    fun packetQueuedDuringProbingFlushesWhileProbePending() = runBlocking {
        val factory = FakeTransportFactory(probeBehavior = { ready() })
        val engine = engineWith(factory)

        val opened = CompletableDeferred<Unit>()
        engine.events.once(
            EngineConnection.EVENT_OPEN,
            EventBus.Listener {
                engine.send(EnginePacket.Message(SocketPacket.Connect("/", null)))
                opened.complete(Unit)
            },
        )
        engine.open()

        withTimeout(2.seconds) { opened.await() }
        val sent = withTimeout(2.seconds) { factory.polling.firstNonPingSend.await() }
        assertTrue((sent.packet as EnginePacket.Message).socket is SocketPacket.Connect)
        engine.close()
    }

    /** Probe opens then immediately closes → onProbeFailed with the CONNECT still buffered. */
    @Test
    fun packetQueuedDuringProbingIsFlushedWhenProbeFails() = runBlocking {
        val factory = FakeTransportFactory(probeBehavior = {
            ready()
            failClosed()
        })
        val engine = engineWith(factory)

        val opened = CompletableDeferred<Unit>()
        engine.events.once(
            EngineConnection.EVENT_OPEN,
            EventBus.Listener {
                engine.send(EnginePacket.Message(SocketPacket.Connect("/", null)))
                opened.complete(Unit)
            },
        )
        engine.open()

        withTimeout(2.seconds) { opened.await() }
        val sent = withTimeout(2.seconds) { factory.polling.firstNonPingSend.await() }
        assertTrue((sent.packet as EnginePacket.Message).socket is SocketPacket.Connect)
        engine.close()
    }

    /**
     * Probe pongs (engine pauses polling for the switch) then the probe socket dies. The engine
     * must resume the paused polling transport so the connection survives on polling, rather than
     * leaving it permanently paused (which tears the connection down on the next send).
     */
    @Test
    fun pollingResumesWhenProbeFailsAfterPong() = runBlocking {
        val factory = FakeTransportFactory(
            probeBehavior = {
                ready()
                deliver(EnginePacket.Pong("probe"))
                failClosed()
            },
        )
        val engine = engineWith(factory)

        val opened = CompletableDeferred<Unit>()
        var closed = false
        engine.events.once(EngineConnection.EVENT_OPEN, EventBus.Listener { opened.complete(Unit) })
        engine.events.on(EngineConnection.EVENT_CLOSE, EventBus.Listener { closed = true })
        engine.open()

        withTimeout(2.seconds) { opened.await() }
        // The engine must un-pause the polling transport after the probe fails.
        withTimeout(2.seconds) { factory.polling.resumed.await() }
        // ...and a subsequent send must go out over the resumed polling transport...
        engine.send(EnginePacket.Message(SocketPacket.Connect("/", null)))
        val sent = withTimeout(2.seconds) { factory.polling.firstNonPingSend.await() }
        assertTrue((sent.packet as EnginePacket.Message).socket is SocketPacket.Connect)
        // ...without the connection being torn down.
        assertFalse(closed)
        engine.close()
    }

    private fun engineWith(factory: FakeTransportFactory): EngineConnection = EngineConnection(
        url = "http://localhost:12345",
        options = socketOptions {
            transports(Transports.POLLING, Transports.WEBSOCKET)
            upgrade = true
            reconnection = false
        },
        scope = scope,
        transportFactory = factory,
    )
}

/**
 * Fake transport. Records outbound packets and completes [firstNonPingSend] the first time it is
 * asked to send anything other than a probe ping. [openBehavior] is the behaviour run on doOpen().
 */
private class FakeTransport(
    options: TransportOptions,
    httpClient: HttpClient,
    log: SocketLog,
    scope: CoroutineScope,
    ioScope: CoroutineScope,
    name: String,
    isProbe: Boolean,
    private val openBehavior: FakeTransport.() -> Unit,
) : Transport(options, httpClient, log, scope, ioScope, name, isProbe) {

    val firstNonPingSend = CompletableDeferred<OutboundEngineMessage>()
    val resumed = CompletableDeferred<Unit>()

    override fun doOpen() {
        openBehavior()
    }

    override fun doSend(messages: List<OutboundEngineMessage>) {
        for (message in messages) {
            val isProbePing = (message.packet as? EnginePacket.Ping)?.payload == PROBE
            if (!isProbePing && !firstNonPingSend.isCompleted) {
                firstNonPingSend.complete(message)
            }
        }
        events.emit(EVENT_DRAIN, drainCount(messages))
    }

    override fun doClose(fromOpenState: Boolean) = Unit

    // Pending pause: mark PAUSED but hold the callback, so an upgrade stalls at PAUSING_POLL
    // (the callback that would send the Upgrade packet is intentionally never invoked).
    override fun pause(onPause: () -> Unit) {
        state = TransportState.PAUSED
    }

    override fun resume() {
        if (state == TransportState.PAUSED) {
            state = TransportState.OPEN
            if (!resumed.isCompleted) resumed.complete(Unit)
        }
    }

    /** Transition to OPEN, optionally delivering an Open handshake packet upstream. */
    fun ready(handshake: EnginePacket.Open? = null) {
        onOpen()
        handshake?.let { onPacket(it) }
    }

    /** Deliver an inbound engine packet upstream (e.g. the probe pong). */
    fun deliver(packet: EnginePacket) = onPacket(packet)

    /** Simulate the probe socket closing (Ktor CIO "Job was cancelled" under CI load). */
    fun failClosed() = onClose()

    // Mirror WebSocketTransport.probeDrainCount: a lone probe ping drains 0 (never triggers upgrade).
    private fun drainCount(messages: List<OutboundEngineMessage>): Int {
        if (!isProbe) return messages.size
        val onlyProbePing = messages.size == 1 &&
            (messages[0].packet as? EnginePacket.Ping)?.payload == PROBE
        return if (onlyProbePing) 0 else messages.size
    }

    private companion object {
        const val PROBE = "probe"
    }
}

/** Returns a fake polling transport that handshakes advertising a websocket upgrade, and a fake
 *  websocket probe transport whose open behaviour is supplied per test. */
private class FakeTransportFactory(
    private val probeBehavior: FakeTransport.() -> Unit,
) : TransportFactory {
    lateinit var polling: FakeTransport
    var probe: FakeTransport? = null

    override fun create(
        name: String,
        options: TransportOptions,
        httpClient: HttpClient,
        scope: CoroutineScope,
        ioScope: CoroutineScope,
        log: SocketLog,
        isProbe: Boolean,
    ): Transport = when (name) {
        PollingTransport.NAME -> FakeTransport(
            options,
            httpClient,
            log,
            scope,
            ioScope,
            name,
            isProbe = false,
            openBehavior = {
                ready(
                    EnginePacket.Open(
                        sid = "fake-sid",
                        pingInterval = 300_000,
                        pingTimeout = 300_000,
                        upgrades = listOf(WebSocketTransport.NAME),
                    ),
                )
            },
        ).also { polling = it }

        WebSocketTransport.NAME -> FakeTransport(
            options,
            httpClient,
            log,
            scope,
            ioScope,
            name,
            isProbe = true,
            openBehavior = probeBehavior,
        ).also { probe = it }

        else -> throw IllegalArgumentException("unexpected transport: $name")
    }
}
