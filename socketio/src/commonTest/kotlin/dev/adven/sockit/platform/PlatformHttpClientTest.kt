package dev.adven.sockit.platform

import kotlin.test.Test
import kotlin.test.assertSame

class PlatformHttpClientTest {
    @Test
    fun externalClientIsReturnedAsIs() {
        val external = createPlatformHttpClient()
        try {
            assertSame(external, createPlatformHttpClient(external = external))
        } finally {
            external.close()
        }
    }
}
