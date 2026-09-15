package dev.adven.sockit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Darwin + Ktor WebSocket runtime smoke (architecture §14, gap #5).
 *
 * Requires the Node echo server on the host — see [PlatformEchoServer].
 *
 * ```bash
 * cd socketio/src/jvmTest/resources && node socket-server.js
 * ./gradlew :socketio:iosSimulatorArm64Test
 * ```
 */
class DarwinSmokeTest {
    @Test
    fun connectWithDefaultTransports() = runBlocking {
        requireEchoServer()
        PlatformSmokeTestCases.connectWithDefaultTransports(PlatformEchoServer.baseUrl)
    }

    @Test
    fun emitEchoRoundTrip() = runBlocking {
        requireEchoServer()
        PlatformSmokeTestCases.emitEchoRoundTrip(PlatformEchoServer.baseUrl, "darwin-smoke")
    }

    @Test
    fun websocketOnlyReachesConnected() = runBlocking {
        requireEchoServer()
        PlatformSmokeTestCases.websocketOnlyReachesConnected(PlatformEchoServer.baseUrl)
    }

    /**
     * Kotlin/Native has no JUnit [Assume]; fail fast so smoke runs are not reported as false passes.
     */
    private suspend fun requireEchoServer() {
        assertTrue(
            PlatformEchoServer.isReachable(),
            "Echo server not reachable at ${PlatformEchoServer.baseUrl}. " +
                "Start: cd socketio/src/jvmTest/resources && node socket-server.js",
        )
    }
}
