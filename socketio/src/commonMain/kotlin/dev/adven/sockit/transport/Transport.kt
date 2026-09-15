package dev.adven.sockit.transport

import dev.adven.sockit.internal.EventBus
import dev.adven.sockit.internal.logging.SocketLog
import dev.adven.sockit.platform.currentTimeMillis
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import dev.adven.sockit.protocol.ProtocolParseException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

internal enum class TransportState {
    INIT,
    OPENING,
    OPEN,
    CLOSING,
    CLOSED,
    PAUSED,
}

internal data class TransportOptions(
    val secure: Boolean = false,
    val hostname: String,
    val port: Int = if (secure) 443 else 80,
    val path: String = "/socket.io/",
    val query: Map<String, String> = emptyMap(),
    val extraHeaders: Map<String, List<String>> = emptyMap(),
    val timestampRequests: Boolean = false,
    val timestampParam: String = "t",
)

internal abstract class Transport(
    protected val options: TransportOptions,
    protected val httpClient: HttpClient,
    protected val log: SocketLog,
    protected val scope: CoroutineScope,
    protected val ioScope: CoroutineScope,
    val name: String,
    val isProbe: Boolean = false,
) {
    val events = EventBus()
    protected var state = TransportState.INIT
    protected var writable = true
    protected var sessionId: String? = options.query["sid"]

    fun open(): Transport {
        log.devVerbose(TAG) { "$name open: state=$state" }
        if (state == TransportState.CLOSED || state == TransportState.INIT) {
            state = TransportState.OPENING
            doOpen()
        }
        return this
    }

    fun send(messages: List<OutboundEngineMessage>) {
        log.devVerbose(TAG) { "$name send: state=$state, count=${messages.size}" }
        if (state == TransportState.OPEN) {
            doSend(messages)
        } else {
            onError("Transport not open")
        }
    }

    fun close(): Transport {
        log.lifecycleInfo(TAG, "$name closing")
        if (state == TransportState.OPENING || state == TransportState.OPEN) {
            val fromOpenState = state == TransportState.OPEN
            state = TransportState.CLOSING
            doClose(fromOpenState)
        }
        return this
    }

    abstract fun pause(onPause: () -> Unit)

    /**
     * Resumes a transport previously [pause]d. Only long-polling actually pauses; for transports
     * whose [pause] is a no-op (websocket, webtransport) this is intentionally a no-op as well.
     */
    open fun resume() {
        // no-op by default; PollingTransport restarts its poll loop.
    }

    protected fun onOpen() {
        log.lifecycleInfo(TAG, "$name connected")
        log.devVerbose(TAG) { "$name onOpen: state=$state" }
        if (state == TransportState.OPENING || state == TransportState.CLOSING) {
            state = TransportState.OPEN
            writable = true
            events.emit(EVENT_OPEN)
        }
    }

    protected fun onPacket(packet: EnginePacket) {
        log.devVerbose(TAG) { "$name onPacket: $packet" }
        events.emit(EVENT_PACKET, packet)
    }

    protected fun onError(message: String) {
        log.lifecycleError(TAG, "$name transport error")
        log.devVerbose(TAG, "$name onError: $message")
        events.emit(EVENT_ERROR, message)
    }

    protected fun onError(cause: Throwable) {
        log.lifecycleError(TAG, "$name transport error", cause)
        log.devVerbose(TAG, "$name onError", cause)
        events.emit(EVENT_ERROR, cause)
    }

    protected fun onParseError(raw: String, cause: Throwable? = null) {
        log.lifecycleError(TAG, "$name parse error")
        log.devVerbose(TAG, "$name parse error: $raw", cause)
        events.emit(EVENT_ERROR, ProtocolParseException(raw, cause))
    }

    protected fun onClose() {
        log.lifecycleInfo(TAG, "$name closed")
        log.devVerbose(TAG) { "$name onClose" }
        state = TransportState.CLOSED
        events.emit(EVENT_CLOSE)
    }

    protected fun uri(secureScheme: String, insecureScheme: String): String {
        val query = options.query.toMutableMap()
        sessionId?.let { query["sid"] = it }
        val scheme = if (options.secure) secureScheme else insecureScheme
        val portSuffix = if (options.port > 0 &&
            ((options.secure && options.port != 443) || (!options.secure && options.port != 80))
        ) {
            ":${options.port}"
        } else {
            ""
        }

        if (options.timestampRequests) {
            query[options.timestampParam] = currentTimeMillis().toString(36)
        }

        val queryString = encodeQuery(query)
        val derivedQuery = if (queryString.isNotEmpty()) "?$queryString" else ""
        val hostname = if (options.hostname.contains(":")) {
            "[${options.hostname}]"
        } else {
            options.hostname
        }
        return "$scheme://$hostname$portSuffix${options.path}$derivedQuery"
    }

    protected abstract fun doOpen()
    protected abstract fun doSend(messages: List<OutboundEngineMessage>)
    protected abstract fun doClose(fromOpenState: Boolean)

    companion object {
        const val EVENT_OPEN = "open"
        const val EVENT_CLOSE = "close"
        const val EVENT_PACKET = "packet"
        const val EVENT_DRAIN = "drain"
        const val EVENT_ERROR = "error"
        const val EVENT_POLL = "poll"
        const val EVENT_POLL_COMPLETE = "pollComplete"
        const val EVENT_REQUEST_HEADERS = "requestHeaders"
        const val EVENT_RESPONSE_HEADERS = "responseHeaders"
        const val EVENT_BINARY = "binary"

        private const val TAG = "Transport"
    }
}

internal fun putHeaders(
    builder: io.ktor.http.HeadersBuilder,
    headers: Map<String, List<String>>,
) {
    headers.forEach { (key, values) ->
        values.forEach { value -> builder.append(key, value) }
    }
}

private fun encodeQuery(query: Map<String, String>): String = query.entries.joinToString("&") { (key, value) ->
    "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
}

private fun encodeQueryComponent(value: String): String = value.map { char ->
    when {
        char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == '~' -> char.toString()
        char == ' ' -> "+"
        else -> "%${char.code.toString(16).uppercase().padStart(2, '0')}"
    }
}.joinToString("")
