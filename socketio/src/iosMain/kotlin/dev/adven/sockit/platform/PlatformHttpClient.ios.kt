package dev.adven.sockit.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLCredential
import platform.Foundation.create
import platform.Foundation.serverTrust
import platform.Security.SecTrustRef

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun createPlatformHttpClient(
    trustAllCerts: Boolean,
    external: HttpClient?,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    external?.let { return it }
    return HttpClient(Darwin) {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
        engine {
            if (trustAllCerts) {
                handleChallenge { _, _, challenge, completionHandler ->
                    val serverTrust: SecTrustRef? = challenge.protectionSpace.serverTrust
                    if (serverTrust != null) {
                        val credential = NSURLCredential.create(trust = serverTrust)
                        completionHandler(0, credential)
                    } else {
                        completionHandler(1, null)
                    }
                }
            }
        }
        configure(this)
    }
}
