package dev.adven.sockit

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketCommand
import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.SocketEvent
import dev.adven.sockit.api.SocketException
import dev.adven.sockit.api.SocketPayload
import dev.adven.sockit.api.Subscribe
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.Unsubscribe
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.SocketTestServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.AfterClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionIntegrationTest {
    private val serverUrl: String
        get() = "http://localhost:${SocketTestServer.port}"

    companion object {
        @AfterClass
        @JvmStatic
        fun stopSharedServer() {
            SocketTestServer.stop()
        }
    }

    @BeforeTest
    fun ensureServerRunning() {
        when {
            SocketTestServer.port == 0 -> {
                SocketTestServer.start()
                SocketTestServer.awaitReady()
            }
            !SocketTestServer.isHealthy() -> SocketTestServer.restartNode()
            else -> Unit
        }
    }

    @AfterTest
    fun repairAfterTest() = runBlocking {
        SocketClientRegistry.resetForTests()
        if (SocketTestServer.port != 0 && !SocketTestServer.isHealthy()) {
            SocketTestServer.restartNode()
        }
    }

    @Test
    fun connectAndDisconnect() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())
        assertEquals(ConnectionState.Connected, socket.connectionState.value)

        socket.close()
        delay(300.milliseconds)
        assertTrue(socket.isDisconnected())
        assertEquals(ConnectionState.Disconnected, socket.connectionState.value)
        client.close()
    }

    @Test
    fun emitOnEchoRoundTrip() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val socket = client.namespace()
        socket.openAwait()

        val received = CompletableDeferred<SocketPayload>()
        val collectJob = async {
            socket.events("echoBack").first().args.firstOrNull()?.let { received.complete(it) }
        }
        delay(100.milliseconds)
        socket.emit("echo", "hello")
        val payload = withTimeout(10.seconds) { received.await() }
        val echoed = when (payload) {
            is SocketPayload.Text -> payload.value
            is SocketPayload.Json -> payload.element.jsonPrimitive.content
            else -> error("unexpected payload: $payload")
        }
        assertEquals("hello", echoed)
        collectJob.cancel()
        client.close()
    }

    @Test
    fun ackEmitReceivesResponse() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val socket = client.namespace()
        socket.openAwait()

        val ackEvent = withTimeout(10.seconds) {
            socket.emitWithAck("ack", "ping", 42).first()
        }
        assertEquals("ack", ackEvent.name)
        assertEquals("ping", ackEvent.args[0].stringValue())
        assertEquals(42, ackEvent.args[1].numberValue())

        client.close()
    }

    private fun SocketPayload.stringValue(): String = when (this) {
        is SocketPayload.Text -> value
        is SocketPayload.Json -> element.jsonPrimitive.content
        else -> error("expected string payload: $this")
    }

    private fun SocketPayload.numberValue(): Int = when (this) {
        is SocketPayload.Json -> element.jsonPrimitive.content.toInt()
        else -> error("expected json number payload: $this")
    }

    /**
     * Requires the Node echo server ([socket-server.js]) — skipped when [SocketTestServer] falls
     * back to the embedded Engine.IO server.
     */
    @Test
    fun reconnectAfterServerDrop() = runBlocking {
        if (SocketTestServer.isEmbedded) {
            // Embedded restart races on port bind in parallel CI; Node echo server is the canonical path.
            return@runBlocking
        }
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.POLLING, Transports.WEBSOCKET)
                reconnection = true
                reconnectionDelayMs = 200
                reconnectionDelayMaxMs = 500
            },
        )
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())

        SocketTestServer.dropActiveServer()
        delay(300.milliseconds)
        withTimeout(15.seconds) {
            socket.connectionState.first {
                it is ConnectionState.Reconnecting || it is ConnectionState.Disconnected
            }
        }

        SocketTestServer.restartNode()
        delay(500.milliseconds)
        withTimeout(30.seconds) {
            socket.connectionState.first { it is ConnectionState.Connected }
        }
        assertTrue(socket.isConnected())
        client.close()
    }

    @Test
    fun websocketOnlyTransport() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())
        client.close()
    }

    @Test
    fun twoNamespacesShareSingleEngine() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val root = client.namespace("/")
        val foo = client.namespace("/foo")

        root.openAwait()
        foo.openAwait()
        assertTrue(root.isConnected())
        assertTrue(foo.isConnected())

        root.close()
        foo.close()
        client.close()
    }

    @Test
    fun closeThenImmediateOpenDoesNotCrash() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.POLLING, Transports.WEBSOCKET)
                upgrade = true
            },
        )
        val socket = client.namespace()
        socket.openAwait()
        socket.close()
        socket.open()
        socket.openAwait()
        assertTrue(socket.isConnected())
        client.close()
    }

    @Test
    fun connectionStateTransitionsExposed() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val socket = client.namespace()
        assertEquals(ConnectionState.Disconnected, socket.connectionState.value)

        withTimeout(10.seconds) { socket.openAwait() }
        assertEquals(ConnectionState.Connected, socket.connectionState.value)

        socket.close()
        withTimeout(5.seconds) {
            while (!socket.isDisconnected()) {
                delay(50.milliseconds)
            }
        }
        assertEquals(ConnectionState.Disconnected, socket.connectionState.value)
        client.close()
    }

    @Test
    fun snapshotHelpersMatchConnectionState() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val socket = client.namespace()
        assertTrue(socket.isDisconnected())
        assertFalse(socket.isConnected())

        socket.openAwait()
        assertTrue(socket.isConnected())
        assertFalse(socket.isDisconnected())

        socket.close()
        delay(300.milliseconds)
        assertTrue(socket.isDisconnected())
        assertFalse(socket.isConnected())
        client.close()
    }

    @Test
    fun clientSnapshotHelpersMatchAggregateState() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        assertTrue(client.isDisconnected())
        assertFalse(client.isConnected())

        client.namespace().openAwait()
        assertTrue(client.isConnected())
        assertFalse(client.isDisconnected())

        client.namespace().close()
        delay(300.milliseconds)
        assertTrue(client.isDisconnected())
        assertFalse(client.isConnected())
        client.close()
    }

    @Test
    fun streamCommandSubscribeAndUnsubscribe() = runBlocking {
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

        val pair = buildJsonObject { put("pair", JsonPrimitive("BTC-INR")) }
        socket.emit(Subscribe(pair))
        socket.emit(Unsubscribe(pair))
        delay(500.milliseconds)

        if (SocketTestServer.isEmbedded) {
            val wire = SocketTestServer.postedBodies().joinToString("\u001e")
            assertTrue(wire.contains("subscribe"), "expected subscribe wire event, got: $wire")
            assertTrue(wire.contains("unsubscribe"), "expected unsubscribe wire event, got: $wire")
            assertTrue(wire.contains("BTC-INR"), "expected payload on wire, got: $wire")
        }
        client.close()
    }

    @Test
    fun socketCommandCustomEvent() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val socket = client.namespace()
        socket.openAwait()

        val received = CompletableDeferred<SocketPayload>()
        val job = async {
            socket.events("echoBack").first().let { event ->
                received.complete(event.args.firstOrNull() ?: error("missing arg"))
            }
        }
        delay(100.milliseconds)
        socket.emit(SocketCommand("echo", JsonPrimitive("custom")))
        val payload = withTimeout(10.seconds) { received.await() }
        when (payload) {
            is SocketPayload.Text -> assertEquals("custom", payload.value)
            is SocketPayload.Json -> assertEquals("custom", payload.element.jsonPrimitive.content)
            else -> error("unexpected payload type: $payload")
        }
        job.cancel()
        client.close()
    }

    @Test
    fun errorsFlowReceivesReservedEventEmitFailure() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val socket = client.namespace()
        socket.openAwait()

        val errorJob = async {
            socket.errors.first { it is SocketError.SendFailed }
        }
        delay(50.milliseconds)
        socket.emit("disconnect")
        val error = withTimeout(5.seconds) { errorJob.await() }
        assertTrue(error is SocketError.SendFailed)
        client.close()
    }

    /**
     * Requires the Node echo server — transport drop + write error timing is validated against Node.
     */
    @Test
    fun errorsFlowReceivesSendFailureAfterTransportDrop() = runBlocking {
        if (SocketTestServer.isEmbedded) {
            // SendFailed on dead transport is covered in EngineConnectionTest; embedded stop does not
            // reliably surface transport write errors on the namespace errors Flow.
            return@runBlocking
        }
        val fastTimeoutClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 500
                requestTimeoutMillis = 2_000
            }
        }
        try {
            val client = SocketClient.connect(
                serverUrl,
                socketOptions {
                    httpClient = fastTimeoutClient
                    transports(Transports.POLLING)
                    upgrade = false
                    reconnection = false
                },
            )
            val socket = client.namespace()
            socket.openAwait()

            val errorJob = async {
                socket.errors.first {
                    it is SocketError.SendFailed || it is SocketError.TransportClosed
                }
            }

            // Race stop with emit while namespace is still marked connected (mirrors EngineConnectionTest).
            coroutineScope {
                launch { SocketTestServer.dropActiveServer() }
                launch {
                    delay(50.milliseconds)
                    socket.emit("echo", "after-drop")
                }
            }

            val error = withTimeout(10.seconds) { errorJob.await() }
            assertTrue(
                error is SocketError.SendFailed || error is SocketError.TransportClosed,
                "expected transport error on errors flow, got $error",
            )
            client.close()
            SocketTestServer.restartNode()
        } finally {
            fastTimeoutClient.close()
        }
    }

    @Test
    fun forceNewBypassesMultiplexCache() = runBlocking {
        val options = socketOptions {
            transports(Transports.POLLING, Transports.WEBSOCKET)
            reconnection = false
            forceNew = true
        }
        val clientA = SocketClient.connect(serverUrl, options)
        val clientB = SocketClient.connect(serverUrl, options)

        clientA.namespace().openAwait()
        clientB.namespace().openAwait()
        assertTrue(clientA.namespace().isConnected())
        assertTrue(clientB.namespace().isConnected())

        clientA.close()
        delay(200.milliseconds)
        assertTrue(clientB.namespace().isConnected(), "forceNew clients must not share lifecycle")

        clientB.close()
    }

    @Test
    fun multiplexRefCountKeepsConnectionAliveUntilLastClientClosed() = runBlocking {
        val options = testOptions()
        val clientA = SocketClient.connect(serverUrl, options)
        val clientB = SocketClient.connect(serverUrl, options)
        val socketA = clientA.namespace()
        val socketB = clientB.namespace("/foo")

        socketA.openAwait()
        socketB.openAwait()
        assertTrue(socketA.isConnected())
        assertTrue(socketB.isConnected())

        clientA.close()
        delay(200.milliseconds)
        assertTrue(socketB.isConnected(), "second client should keep shared engine alive")

        clientB.close()
    }

    @Test
    fun binaryEventRoundTripThroughNamespaceSocket() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        val received = CompletableDeferred<SocketEvent>()
        val collectJob = async {
            received.complete(socket.events("echoBinaryBack").first())
        }
        delay(100.milliseconds)
        socket.emit("echoBinary")
        val event = withTimeout(10.seconds) { received.await() }
        assertEquals("echoBinaryBack", event.name)
        assertEquals(1, event.args.size)
        val payload = event.args.single()
        assertTrue(payload is SocketPayload.Binary, "expected reassembled binary as Binary, got $payload")
        assertEquals(ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)), payload.bytes)

        collectJob.cancel()
        client.close()
    }

    /**
     * Requires the Node echo server — its 'echo' handler passes through arbitrary args, unlike
     * the embedded server's single-arg-only echo.
     */
    @Test
    fun mixedBinaryAndScalarArgsPreserveOrderOnEmit() = runBlocking {
        if (SocketTestServer.isEmbedded) {
            return@runBlocking
        }
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        val received = CompletableDeferred<SocketEvent>()
        val collectJob = async {
            received.complete(socket.events("echoBack").first())
        }
        delay(100.milliseconds)
        val bytes = ByteString(byteArrayOf(0x0A, 0x0B, 0x0C))
        socket.emit("echo", "before", bytes, "after")

        val event = withTimeout(10.seconds) { received.await() }
        assertEquals(3, event.args.size)
        assertEquals("before", (event.args[0] as SocketPayload.Json).element.jsonPrimitive.content)
        assertTrue(event.args[1] is SocketPayload.Binary, "expected binary in the middle position, got ${event.args[1]}")
        assertEquals(bytes, (event.args[1] as SocketPayload.Binary).bytes)
        assertEquals("after", (event.args[2] as SocketPayload.Json).element.jsonPrimitive.content)

        collectJob.cancel()
        client.close()
    }

    @Test
    fun respondsToServerInitiatedAck() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        // Server 'callAck' makes the server emit 'ack' with an acknowledgement callback,
        // then re-emit 'ackBack' carrying whatever the client acked with.
        val ackBack = CompletableDeferred<SocketEvent>()
        val ackBackJob = async { ackBack.complete(socket.events("ackBack").first()) }
        val respondJob = async {
            val serverEvent = socket.events("ack").first()
            assertNotNull(serverEvent.ack, "server 'ack' event should carry an ack responder")
            serverEvent.ack?.send("hello", "world")
        }
        delay(100.milliseconds)
        socket.emit("callAck")

        val event = withTimeout(10.seconds) { ackBack.await() }
        assertEquals("ackBack", event.name)
        val contents = event.args.map { (it as SocketPayload.Json).element.jsonPrimitive.content }
        assertEquals(listOf("hello", "world"), contents)

        respondJob.cancel()
        ackBackJob.cancel()
        client.close()
    }

    @Test
    fun respondsToServerInitiatedAckEvenWhenSubscribedAfterItArrives() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        // Trigger the server's ack-requesting 'ack' event with NO subscriber for "ack" yet, and
        // wait for the round trip to actually land before subscribing — the client must buffer
        // the event (and its ack responder) rather than drop it.
        socket.emit("callAck")
        delay(300.milliseconds)

        val ackBack = CompletableDeferred<SocketEvent>()
        val ackBackJob = async { ackBack.complete(socket.events("ackBack").first()) }
        val respondJob = async {
            val serverEvent = socket.events("ack").first()
            assertNotNull(serverEvent.ack, "server 'ack' event should carry an ack responder")
            serverEvent.ack?.send("late", "subscriber")
        }

        val event = withTimeout(10.seconds) { ackBack.await() }
        assertEquals("ackBack", event.name)
        val contents = event.args.map { (it as SocketPayload.Json).element.jsonPrimitive.content }
        assertEquals(listOf("late", "subscriber"), contents)

        respondJob.cancel()
        ackBackJob.cancel()
        client.close()
    }

    @Test
    fun ackSendAfterDisconnectReportsSendFailedOnErrorsFlow() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        val serverAckEvent = CompletableDeferred<SocketEvent>()
        val ackJob = async { serverAckEvent.complete(socket.events("ack").first()) }
        delay(100.milliseconds)
        socket.emit("callAck")
        val event = withTimeout(10.seconds) { serverAckEvent.await() }
        assertNotNull(event.ack)

        val errorJob = async { socket.errors.first { it is SocketError.SendFailed } }
        socket.close()
        delay(200.milliseconds)
        event.ack?.send("too", "late")

        val error = withTimeout(5.seconds) { errorJob.await() }
        assertTrue(error is SocketError.SendFailed)

        ackJob.cancel()
        client.close()
    }

    @Test
    fun connectErrorExposesStructuredMessageAndData() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
            },
        )
        // The "/no" namespace rejects with Error("auth failed") + data {a:"b", c:3}.
        val socket = client.namespace("/no")
        socket.open()

        val failure = withTimeout(10.seconds) {
            socket.connectionState.first { it is ConnectionState.Failed } as ConnectionState.Failed
        }
        val error = failure.error as SocketError.ConnectError
        assertEquals("auth failed", error.message)
        assertEquals("""{"a":"b","c":3}""", error.data)

        client.close()
    }

    @Test
    fun authPayloadReachesServerHandshake() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
                auth {
                    put("token", "jwt-123")
                    putJsonObject("meta") { put("deviceId", 42) }
                }
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        // 'getHandshake' acks with socket.handshake, whose `auth` echoes what we sent.
        val ack = withTimeout(10.seconds) { socket.emitWithAck("getHandshake").first() }
        val handshake = (ack.args.single() as SocketPayload.Json).element.jsonObject
        val auth = handshake["auth"]!!.jsonObject
        assertEquals("jwt-123", auth["token"]!!.jsonPrimitive.content)
        assertEquals(42, auth["meta"]!!.jsonObject["deviceId"]!!.jsonPrimitive.int)

        client.close()
    }

    @Test
    fun emitWithAckTimesOutWhenServerNeverAcks() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
                ackTimeoutMs = 500
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        // No server handler for this event, so no ACK ever comes back: the flow must fail
        // via the ack timeout rather than hang forever.
        val error = assertFailsWith<SocketException.SendFailed> {
            withTimeout(5.seconds) {
                socket.emitWithAck("neverAckedEvent", "ping").first()
            }
        }
        assertTrue(
            error.error is SocketError.Timeout,
            "expected ack timeout, got ${error.error}",
        )

        client.close()
    }

    @Test
    fun ackTimeoutDoesNotMisfireAfterFastAck() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
                ackTimeoutMs = 300
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        val timeoutErrorJob = async { socket.errors.first { it is SocketError.Timeout } }

        // Fire several fast round trips well under ackTimeoutMs; each must resolve normally.
        repeat(20) { i ->
            val ack = withTimeout(5.seconds) { socket.emitWithAck("ack", "ping-$i").first() }
            assertEquals("ping-$i", (ack.args[0] as SocketPayload.Json).element.jsonPrimitive.content)
        }

        // Wait past ackTimeoutMs once more to catch a late/leaked timer firing spuriously.
        delay(500.milliseconds)
        assertFalse(timeoutErrorJob.isCompleted, "ack timeout must not fire once the ack already resolved")

        timeoutErrorJob.cancel()
        client.close()
    }

    @Test
    fun emitAwaitStreamCommandSubscribes() = runBlocking {
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

        val pair = buildJsonObject { put("pair", JsonPrimitive("ETH-INR")) }
        socket.emitAwait(Subscribe(pair))
        delay(500.milliseconds)

        if (SocketTestServer.isEmbedded) {
            val wire = SocketTestServer.postedBodies().joinToString("\u001e")
            assertTrue(wire.contains("subscribe"), "expected subscribe wire event, got: $wire")
            assertTrue(wire.contains("ETH-INR"), "expected payload on wire, got: $wire")
        }
        client.close()
    }

    @Test
    fun emitAwaitRejectsReservedEventNames() = runBlocking {
        val client = SocketClient.connect(serverUrl, testOptions())
        val socket = client.namespace()
        socket.openAwait()

        assertFailsWith<SocketException.SendFailed> {
            socket.emitAwait("connect")
        }
        client.close()
    }

    @Test
    fun sharedHttpClientConnects() = runBlocking {
        val sharedClient = HttpClient(CIO) {
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
        }
        try {
            val client = SocketClient.connect(
                serverUrl,
                socketOptions {
                    httpClient = sharedClient
                    transports(Transports.POLLING, Transports.WEBSOCKET)
                },
            )
            val socket = client.namespace()
            socket.openAwait()
            assertTrue(socket.isConnected())
            client.close()
        } finally {
            sharedClient.close()
        }
    }

    private fun testOptions() = socketOptions {
        transports(Transports.POLLING, Transports.WEBSOCKET)
        reconnection = false
    }
}
