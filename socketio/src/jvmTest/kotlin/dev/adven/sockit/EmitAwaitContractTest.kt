package dev.adven.sockit

import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.SocketException
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.SocketTestServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Proves Option A queue semantics for [dev.adven.sockit.api.NamespaceSocket.emitAwait]:
 * completes on enqueue; async transport [SocketError.SendFailed] surfaces on [errors] only.
 *
 * Requires Node echo server — see [SocketTestServer].
 */
class EmitAwaitContractTest {
    private val serverUrl: String
        get() = "http://localhost:${SocketTestServer.port}"

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
    fun emitAwaitCompletesOnQueueAsyncSendFailedOnErrorsOnly() = runBlocking {
        if (SocketTestServer.isEmbedded) {
            // Node drop reliably surfaces async transport SendFailed on the namespace errors Flow.
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

            // emitAwait must complete without throw when enqueue succeeds.
            socket.emitAwait("echo", "queued-payload")

            val errorJob = async {
                socket.errors.first {
                    it is SocketError.SendFailed || it is SocketError.TransportClosed
                }
            }

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
                "expected async transport error on errors flow, got $error",
            )
            client.close()
            SocketTestServer.restartNode()
        } finally {
            fastTimeoutClient.close()
        }
    }

    @Test
    fun emitAwaitThrowsOnReservedEventPreQueueReject() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.POLLING, Transports.WEBSOCKET)
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        assertFailsWith<SocketException.SendFailed> {
            socket.emitAwait("connect")
        }
        client.close()
    }
}
