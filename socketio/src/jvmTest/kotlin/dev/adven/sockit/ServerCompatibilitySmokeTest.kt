package dev.adven.sockit

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketPayload
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.SocketTestServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-version smoke against the Node echo server ([socket-server.js]).
 *
 * CI runs this class per [socket.io](https://www.npmjs.com/package/socket.io) server version via the
 * `server-compatibility` workflow matrix (`npm install socket.io@<version>` before jvmTest).
 *
 * Requires Node + `node_modules`; skipped when [SocketTestServer] falls back to embedded Engine.IO.
 */
class ServerCompatibilitySmokeTest {
    private val serverUrl: String
        get() = "http://localhost:${SocketTestServer.port}"

    @BeforeTest
    fun ensureNodeEchoServer() {
        if (SocketTestServer.port == 0) {
            SocketTestServer.start()
        } else {
            SocketTestServer.restartNode()
        }
        SocketTestServer.awaitReady()
        require(!SocketTestServer.isEmbedded) {
            "ServerCompatibilitySmokeTest requires the Node echo server (node + node_modules)"
        }
    }

    @AfterTest
    fun cleanup() = runBlocking {
        SocketClientRegistry.resetForTests()
        if (!SocketTestServer.isHealthy()) {
            SocketTestServer.restartNode()
        }
    }

    @Test
    fun pollingHandshakeAndNamespaceConnect() = runBlocking {
        val client = SocketClient.connect(serverUrl, smokeOptions())
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())
        assertEquals(ConnectionState.Connected, socket.connectionState.value)
        client.close()
    }

    @Test
    fun echoEventRoundTrip() = runBlocking {
        val client = SocketClient.connect(serverUrl, smokeOptions())
        val socket = client.namespace()
        socket.openAwait()

        val received = CompletableDeferred<SocketPayload>()
        val collectJob = async {
            socket.events("echoBack").first().args.firstOrNull()?.let { received.complete(it) }
        }
        delay(100.milliseconds)
        socket.emit("echo", "compat-smoke")
        val payload = withTimeout(10.seconds) { received.await() }
        val echoed = when (payload) {
            is SocketPayload.Text -> payload.value
            is SocketPayload.Json -> payload.element.jsonPrimitive.content
            else -> error("unexpected payload: $payload")
        }
        assertEquals("compat-smoke", echoed)
        collectJob.cancel()
        client.close()
    }

    @Test
    fun ackEmitRoundTrip() = runBlocking {
        val client = SocketClient.connect(serverUrl, smokeOptions())
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

    @Test
    fun websocketOnlyTransportConnects() = runBlocking {
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
        assertTrue(socket.isConnected())
        client.close()
    }

    private fun smokeOptions() = socketOptions {
        transports(Transports.POLLING, Transports.WEBSOCKET)
        reconnection = false
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
}
