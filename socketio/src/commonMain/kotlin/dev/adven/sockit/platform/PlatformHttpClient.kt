package dev.adven.sockit.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

internal expect fun createPlatformHttpClient(
    trustAllCerts: Boolean = false,
    external: HttpClient? = null,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient
