package dev.adven.sockit.transport

import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.platform.createPlatformHttpClient
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class HttpClientFactoryTest {
    @Test
    fun reusesInjectedHttpClient() {
        val shared = createPlatformHttpClient()
        try {
            val options = socketOptions { httpClient = shared }
            val created = DefaultHttpClientFactory.create(options)
            assertSame(shared, created)
        } finally {
            shared.close()
        }
    }

    @Test
    fun createsPlatformClientWhenNoneInjected() {
        val options = socketOptions { }
        val first = DefaultHttpClientFactory.create(options)
        val second = DefaultHttpClientFactory.create(options)
        try {
            assertNotSame(first, second)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun trustAllCertsCreatesPlatformClient() {
        val options = socketOptions { trustAllCerts = true }
        val client = DefaultHttpClientFactory.create(options)
        client.close()
    }

    @Test
    fun injectedClientTakesPrecedenceOverTrustAllCerts() {
        val shared = createPlatformHttpClient()
        try {
            val options = socketOptions {
                httpClient = shared
                trustAllCerts = true
            }
            assertSame(shared, DefaultHttpClientFactory.create(options))
        } finally {
            shared.close()
        }
    }
}
