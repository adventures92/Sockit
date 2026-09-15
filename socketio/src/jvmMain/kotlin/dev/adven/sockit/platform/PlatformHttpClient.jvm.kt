package dev.adven.sockit.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

internal actual fun createPlatformHttpClient(
    trustAllCerts: Boolean,
    external: HttpClient?,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    external?.let { return it }
    return HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
        if (trustAllCerts) {
            engine {
                https {
                    trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }
                }
            }
        }
        configure(this)
    }
}
