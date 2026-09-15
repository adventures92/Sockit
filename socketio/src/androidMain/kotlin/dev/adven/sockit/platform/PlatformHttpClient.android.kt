package dev.adven.sockit.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal actual fun createPlatformHttpClient(
    trustAllCerts: Boolean,
    external: HttpClient?,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    external?.let { return it }
    return HttpClient(OkHttp) {
        if (trustAllCerts) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            engine {
                config {
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
        configure(this)
    }
}
