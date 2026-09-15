package dev.adven.sockit.transport

import dev.adven.sockit.api.SocketOptions
import dev.adven.sockit.internal.logging.SocketLog
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

internal interface TransportFactory {
    fun create(
        name: String,
        options: TransportOptions,
        httpClient: HttpClient,
        scope: CoroutineScope,
        ioScope: CoroutineScope = scope,
        log: SocketLog,
        isProbe: Boolean = false,
    ): Transport
}

internal object DefaultTransportFactory : TransportFactory {
    override fun create(
        name: String,
        options: TransportOptions,
        httpClient: HttpClient,
        scope: CoroutineScope,
        ioScope: CoroutineScope,
        log: SocketLog,
        isProbe: Boolean,
    ): Transport = when (name) {
        PollingTransport.NAME -> PollingTransport(
            options = options,
            httpClient = httpClient,
            log = log,
            scope = scope,
            ioScope = ioScope,
        )

        WebSocketTransport.NAME -> WebSocketTransport(
            options = options,
            httpClient = httpClient,
            log = log,
            scope = scope,
            ioScope = ioScope,
            isProbe = isProbe,
        )

        WebTransportTransport.NAME -> WebTransportTransport(
            options = options,
            httpClient = httpClient,
            log = log,
            scope = scope,
            ioScope = ioScope,
            isProbe = isProbe,
        )

        else -> throw IllegalArgumentException(
            "Unknown transport: $name — consumer lists are normalized in SocketOptions.build()",
        )
    }
}

internal fun buildTransportOptions(
    socketOptions: SocketOptions,
    hostname: String,
    port: Int,
    secure: Boolean,
    transportName: String,
    sid: String? = null,
    extraQuery: Map<String, String> = emptyMap(),
): TransportOptions {
    val query = buildMap {
        put("EIO", "4")
        put("transport", transportName)
        sid?.let { put("sid", it) }
        putAll(extraQuery)
    }
    return TransportOptions(
        secure = secure,
        hostname = hostname,
        port = port,
        path = socketOptions.path,
        query = query,
        extraHeaders = socketOptions.extraHeaders,
    )
}
