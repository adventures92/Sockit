package dev.adven.sockit

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.socketio.SocketClientRegistry
import dev.adven.sockit.transport.SocketTestServer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClientCloseAwaitTest {
    private val serverUrl: String
        get() = "http://localhost:${SocketTestServer.port}"

    @BeforeTest
    fun startServer() {
        SocketTestServer.start()
        SocketTestServer.awaitReady()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        SocketClientRegistry.resetForTests()
        SocketTestServer.stop()
    }

    @Test
    fun disconnectedIsTheFinalConnectionStateAfterCloseAwait() = runBlocking {
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
        assertTrue(client.isConnected())

        val seen = mutableListOf<ConnectionState>()
        val collector = launch {
            client.connectionState.collect { seen += it }
        }

        client.closeAwait()

        // closeAwait guarantees the WORKER has finished releasing and destroying the engine — it
        // cannot guarantee this collector has been resumed to observe the resulting Disconnected.
        // Both resumptions land on the same runBlocking event loop and their order is not defined,
        // so snapshotting here raced the final emission: when closeAwait resumed first the
        // snapshot missed Disconnected, which then arrived during the wait below and failed the
        // comparison. That is a property of coroutine scheduling, not of the library.
        //
        // Wait for the terminal state to actually be OBSERVED, then assert nothing follows it.
        // That is the real contract this test exists to pin: Disconnected is the last word.
        try {
            withTimeout(5.seconds) {
                while (seen.lastOrNull() != ConnectionState.Disconnected) {
                    delay(10.milliseconds)
                }
            }
        } catch (_: TimeoutCancellationException) {
            // A bare TimeoutCancellationException here reads like a slow runner, which is exactly
            // the misreading that once sent this failure down a wrong diagnosis. Name the states.
            fail("connectionState never reached Disconnected after closeAwait, saw: $seen")
        }

        // Waiting for Disconnected means the window checked below starts later than closeAwait's
        // return, so assert separately that closing was clean: a well-behaved close walks straight
        // to Disconnected, and a Reconnecting or Failed on the way is a regression this test would
        // otherwise swallow now that the terminal state is what gates the wait.
        assertTrue(
            seen.none { it is ConnectionState.Reconnecting || it is ConnectionState.Failed },
            "closeAwait must not pass through Reconnecting or Failed, saw: $seen",
        )

        val countAtDisconnected = seen.size
        delay(500.milliseconds)
        assertEquals(countAtDisconnected, seen.size, "no connectionState emissions after Disconnected, saw: $seen")
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
        assertTrue(client.isDisconnected())

        collector.cancel()
    }
}
