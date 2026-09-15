package dev.adven.sockit.transport

import dev.adven.sockit.api.Logger
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.internal.logging.SocketLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransportFactoryTest {
    @Test
    fun createsPollingTransport() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DefaultHttpClientFactory.create(socketOptions { })
        try {
            val transport = DefaultTransportFactory.create(
                name = PollingTransport.NAME,
                options = buildTransportOptions(
                    socketOptions = socketOptions { },
                    hostname = "localhost",
                    port = 3000,
                    secure = false,
                    transportName = PollingTransport.NAME,
                ),
                httpClient = client,
                scope = scope,
                log = SocketLog(Logger.NoOp),
            )
            assertIs<PollingTransport>(transport)
        } finally {
            client.close()
        }
    }

    @Test
    fun createsWebSocketTransport() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DefaultHttpClientFactory.create(socketOptions { })
        try {
            val transport = DefaultTransportFactory.create(
                name = WebSocketTransport.NAME,
                options = buildTransportOptions(
                    socketOptions = socketOptions { },
                    hostname = "localhost",
                    port = 3000,
                    secure = false,
                    transportName = WebSocketTransport.NAME,
                ),
                httpClient = client,
                scope = scope,
                log = SocketLog(Logger.NoOp),
                isProbe = true,
            )
            assertIs<WebSocketTransport>(transport)
            assertEquals(true, transport.isProbe)
        } finally {
            client.close()
        }
    }

    @Test
    fun createsWebTransportStub() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DefaultHttpClientFactory.create(socketOptions { })
        try {
            val transport = DefaultTransportFactory.create(
                name = WebTransportTransport.NAME,
                options = buildTransportOptions(
                    socketOptions = socketOptions { experimentalWebTransport = true },
                    hostname = "localhost",
                    port = 443,
                    secure = true,
                    transportName = WebTransportTransport.NAME,
                ),
                httpClient = client,
                scope = scope,
                log = SocketLog(Logger.NoOp),
            )
            assertIs<WebTransportTransport>(transport)
        } finally {
            client.close()
        }
    }
}
