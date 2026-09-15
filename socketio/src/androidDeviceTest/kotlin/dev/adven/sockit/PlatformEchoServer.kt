package dev.adven.sockit

import dev.adven.sockit.platform.createPlatformHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/**
 * Echo server reachability for platform smoke tests.
 *
 * **Prerequisite:** On the host machine, start the Node echo server:
 * ```
 * cd socketio/src/jvmTest/resources && npm ci && node socket-server.js
 * ```
 *
 * Android emulator uses [HOST] (`10.0.2.2`) to reach the host loopback.
 * Physical devices need the host LAN IP instead of [HOST].
 */
internal object PlatformEchoServer {
    const val PORT = 3000
    const val HOST = "10.0.2.2"

    val baseUrl: String
        get() = "http://$HOST:$PORT"

    suspend fun isReachable(): Boolean = try {
        createPlatformHttpClient().use { client ->
            val response: HttpResponse = client.get("$baseUrl/socket.io/?EIO=4&transport=polling")
            response.status.isSuccess()
        }
    } catch (_: Exception) {
        false
    }
}
