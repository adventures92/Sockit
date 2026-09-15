package dev.adven.sockit

import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketPayload
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.EmbeddedEngineIoServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(UnsafeByteStringApi::class)
class BinaryEmitIntegrationTest {
    @BeforeTest
    fun startServer() {
        EmbeddedEngineIoServer.configure()
        EmbeddedEngineIoServer.start()
    }

    @AfterTest
    fun stopServer() = runBlocking {
        SocketClientRegistry.resetForTests()
        EmbeddedEngineIoServer.stop()
    }

    @Test
    fun websocketBinaryEmitRoundTrip() = runBlocking {
        val url = "http://localhost:${EmbeddedEngineIoServer.port}"
        val client = SocketClient.connect(
            url,
            socketOptions {
                transports(Transports.WEBSOCKET)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        val payload = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val received = CompletableDeferred<SocketPayload>()
        val collectJob = async {
            socket.events("echoBinaryBack").collect { event ->
                event.args.firstOrNull()?.let { received.complete(it) }
            }
        }
        delay(100.milliseconds)
        socket.emit("echoBinary", payload)

        val echoed = withTimeout(10.seconds) { received.await() }
        collectJob.cancel()
        assertTrue(echoed is SocketPayload.Binary, "expected binary payload, got $echoed")
        assertEquals(payload, echoed.bytes)

        client.close()
    }

    @Test
    fun pollingBinaryEmitRoundTrip() = runBlocking {
        val url = "http://localhost:${EmbeddedEngineIoServer.port}"
        val client = SocketClient.connect(
            url,
            socketOptions {
                transports(Transports.POLLING)
                upgrade = false
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        val payload = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val received = CompletableDeferred<SocketPayload>()
        val collectJob = async {
            socket.events("echoBinaryBack").collect { event ->
                event.args.firstOrNull()?.let { received.complete(it) }
            }
        }
        delay(100.milliseconds)
        socket.emit("echoBinary", payload)

        val echoed = withTimeout(10.seconds) { received.await() }
        collectJob.cancel()
        assertTrue(echoed is SocketPayload.Binary, "expected binary payload, got $echoed")
        assertEquals(payload, echoed.bytes)

        client.close()
    }
}
