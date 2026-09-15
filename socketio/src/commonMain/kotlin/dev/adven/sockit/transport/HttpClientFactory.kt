package dev.adven.sockit.transport

import dev.adven.sockit.api.SocketOptions
import dev.adven.sockit.platform.createPlatformHttpClient
import io.ktor.client.HttpClient

internal interface HttpClientFactory {
    fun create(options: SocketOptions): HttpClient
}

internal object DefaultHttpClientFactory : HttpClientFactory {
    override fun create(options: SocketOptions): HttpClient = options.httpClient ?: createPlatformHttpClient(
        trustAllCerts = options.trustAllCerts,
    )
}
