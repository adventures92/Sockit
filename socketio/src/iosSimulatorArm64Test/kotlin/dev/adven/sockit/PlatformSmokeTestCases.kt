package dev.adven.sockit

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketPayload
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Shared platform smoke scenarios (architecture §14, diagrams §12).
 *
 * Keep in sync with the copy under `androidDeviceTest/kotlin/.../PlatformSmokeTestCases.kt`.
 *
 * OkHttp (Android) and Darwin (iOS) wrappers call these with platform echo-server URLs.
 */
internal object PlatformSmokeTestCases {
    internal fun defaultSmokeOptions() = socketOptions {
        transports(Transports.POLLING, Transports.WEBSOCKET)
        reconnection = false
    }

    internal fun websocketOnlyOptions() = socketOptions {
        transports(Transports.WEBSOCKET)
        upgrade = false
        reconnection = false
    }

    suspend fun connectWithDefaultTransports(baseUrl: String) {
        val client = SocketClient.connect(baseUrl, defaultSmokeOptions())
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())
        assertEquals(ConnectionState.Connected, socket.connectionState.value)

        socket.close()
        delay(300.milliseconds)
        assertTrue(socket.isDisconnected())
        client.close()
    }

    suspend fun emitEchoRoundTrip(baseUrl: String, payload: String) {
        val client = SocketClient.connect(baseUrl, defaultSmokeOptions())
        val socket = client.namespace()
        socket.openAwait()

        coroutineScope {
            val received = CompletableDeferred<SocketPayload>()
            val collectJob = async {
                socket.events("echoBack").first().args.firstOrNull()?.let { received.complete(it) }
            }
            delay(100.milliseconds)
            socket.emit("echo", payload)
            val echoedPayload = withTimeout(15.seconds) { received.await() }
            val echoed = when (echoedPayload) {
                is SocketPayload.Text -> echoedPayload.value
                is SocketPayload.Json -> echoedPayload.element.jsonPrimitive.content
                else -> error("unexpected payload: $echoedPayload")
            }
            assertEquals(payload, echoed)
            collectJob.cancel()
        }
        client.close()
    }

    suspend fun websocketOnlyReachesConnected(baseUrl: String) {
        val client = SocketClient.connect(baseUrl, websocketOnlyOptions())
        val socket = client.namespace()
        socket.openAwait()
        assertTrue(socket.isConnected())
        client.close()
    }
}
