package dev.adven.sockit

import app.cash.turbine.test
import dev.adven.sockit.api.ConnectionState
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionStateTurbineTest {
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
    fun connectionStateTransitionsDisconnectedConnectingConnected() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.POLLING, Transports.WEBSOCKET)
                reconnection = false
            },
        )
        val socket = client.namespace()

        client.connectionState.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())
            socket.open()
            assertEquals(ConnectionState.Connecting, awaitItem())
            assertEquals(ConnectionState.Connected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        client.close()
    }

    @Test
    fun namespaceErrorsEmitSendFailedOnReservedEvent() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.POLLING, Transports.WEBSOCKET)
                reconnection = false
            },
        )
        val socket = client.namespace()
        socket.openAwait()

        socket.errors.test {
            assertFailsWith<SocketException.SendFailed> {
                socket.emitAwait("connect")
            }
            val error = awaitItem()
            assertTrue(error is SocketError.SendFailed)
            cancelAndIgnoreRemainingEvents()
        }
        client.close()
    }

    @Test
    fun clientAggregateErrorsReceivesNamespaceError() = runBlocking {
        val client = SocketClient.connect(
            serverUrl,
            socketOptions {
                transports(Transports.POLLING, Transports.WEBSOCKET)
                reconnection = false
            },
        )
        val root = client.namespace("/")
        val foo = client.namespace("/foo")
        root.openAwait()
        foo.openAwait()

        client.errors.test {
            assertFailsWith<SocketException.SendFailed> {
                foo.emitAwait("connect")
            }
            val error = awaitItem()
            assertTrue(error is SocketError.SendFailed)
            cancelAndIgnoreRemainingEvents()
        }
        root.close()
        foo.close()
        client.close()
    }

    @Test
    fun asyncTransportSendFailedSurfacesOnErrorsFlow() = runBlocking {
        if (SocketTestServer.isEmbedded) return@runBlocking

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
            socket.emitAwait("echo", "queued-payload")

            socket.errors.test {
                coroutineScope {
                    launch { SocketTestServer.dropActiveServer() }
                    launch {
                        delay(50.milliseconds)
                        socket.emit("echo", "after-drop")
                    }
                }
                var error: SocketError
                do {
                    error = withTimeout(10.seconds) { awaitItem() }
                } while (error !is SocketError.SendFailed && error !is SocketError.TransportClosed)
                cancelAndIgnoreRemainingEvents()
            }
            client.close()
            SocketTestServer.restartNode()
        } finally {
            fastTimeoutClient.close()
        }
    }
}
