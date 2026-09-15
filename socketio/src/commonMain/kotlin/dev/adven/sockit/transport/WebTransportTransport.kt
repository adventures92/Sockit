package dev.adven.sockit.transport

import dev.adven.sockit.internal.logging.SocketLog
import dev.adven.sockit.protocol.OutboundEngineMessage
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/**
 * Engine.IO WebTransport wire transport — **stub only** (CH3 G-P3-3).
 *
 * Registered when [dev.adven.sockit.api.SocketOptionsBuilder.experimentalWebTransport]
 * is `true`. Opens fail fast until a platform WebTransport bridge exists; see
 * `docs/webtransport-feasibility-spike.md`.
 */
internal class WebTransportTransport(
    options: TransportOptions,
    httpClient: HttpClient,
    log: SocketLog,
    scope: CoroutineScope,
    ioScope: CoroutineScope = scope,
    isProbe: Boolean = false,
) : Transport(options, httpClient, log, scope, ioScope, NAME, isProbe) {

    override fun pause(onPause: () -> Unit) {
        onPause()
    }

    override fun doOpen() {
        onError(NOT_IMPLEMENTED_MESSAGE)
    }

    override fun doSend(messages: List<OutboundEngineMessage>) {
        onError(NOT_IMPLEMENTED_MESSAGE)
    }

    override fun doClose(fromOpenState: Boolean) {
        onClose()
    }

    companion object {
        const val NAME: String = "webtransport"

        private const val NOT_IMPLEMENTED_MESSAGE =
            "WebTransport is not implemented on mobile targets — see docs/webtransport-feasibility-spike.md"
    }
}
