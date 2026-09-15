package dev.adven.sockit

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.runner.RunWith
import kotlin.test.Test

/**
 * OkHttp + Ktor WebSocket runtime smoke (architecture §14, gap #5).
 *
 * Requires the Node echo server on the host — see [PlatformEchoServer].
 *
 * ```bash
 * cd socketio/src/jvmTest/resources && node socket-server.js
 * ./gradlew :socketio:connectedAndroidDeviceTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OkHttpSmokeTest {
    @Test
    fun connectWithDefaultTransports() = runBlocking {
        assumeEchoServer()
        PlatformSmokeTestCases.connectWithDefaultTransports(PlatformEchoServer.baseUrl)
    }

    @Test
    fun emitEchoRoundTrip() = runBlocking {
        assumeEchoServer()
        PlatformSmokeTestCases.emitEchoRoundTrip(PlatformEchoServer.baseUrl, "okhttp-smoke")
    }

    @Test
    fun websocketOnlyReachesConnected() = runBlocking {
        assumeEchoServer()
        PlatformSmokeTestCases.websocketOnlyReachesConnected(PlatformEchoServer.baseUrl)
    }

    private suspend fun assumeEchoServer() {
        Assume.assumeTrue(
            "Echo server not reachable at ${PlatformEchoServer.baseUrl}. " +
                "Start: cd socketio/src/jvmTest/resources && node socket-server.js",
            PlatformEchoServer.isReachable(),
        )
    }
}
