package dev.adven.sockit

import dev.adven.sockit.platform.createPlatformHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/**
 * Echo server reachability for iOS simulator smoke tests.
 *
 * **Prerequisite:** On the host machine, start the Node echo server:
 * ```
 * cd socketio/src/jvmTest/resources && npm ci && node socket-server.js
 * ```
 *
 * iOS Simulator reaches the host via `localhost`.
 */
internal object PlatformEchoServer {
    const val PORT = 3000
    const val HOST = "localhost"

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
