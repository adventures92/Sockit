package dev.adven.sockit.transport

import dev.adven.sockit.internal.logging.SocketLog
import dev.adven.sockit.protocol.EngineIoCodec
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString

internal class WebSocketTransport(
    options: TransportOptions,
    httpClient: HttpClient,
    log: SocketLog,
    scope: CoroutineScope,
    ioScope: CoroutineScope = scope,
    isProbe: Boolean = false,
) : Transport(options, httpClient, log, scope, ioScope, NAME, isProbe) {
    private var ws: WebSocketSession? = null

    override fun pause(onPause: () -> Unit) {
        onPause()
    }

    override fun doOpen() {
        val requestUri = uri()
        log.devVerbose(TAG) { "doOpen $requestUri" }

        val requestHeaders = LinkedHashMap<String, List<String>>()
        requestHeaders.putAll(options.extraHeaders)
        events.emit(EVENT_REQUEST_HEADERS, requestHeaders)

        ioScope.launch {
            try {
                httpClient.webSocket(requestUri) {
                    // gap #3: unconditional onOpen — first action after WS handshake
                    onOpen()
                    ws = this

                    // Best-effort response headers; must not block open
                    try {
                        val responseHeaders = call.response.headers.entries().associate { entry ->
                            entry.key to entry.value
                        }
                        scope.launch { events.emit(EVENT_RESPONSE_HEADERS, responseHeaders) }
                    } catch (_: Exception) {
                        // ignore
                    }

                    listen()
                }
            } catch (e: Exception) {
                scope.launch { onError(e) }
            }
        }
    }

    private suspend fun listen() {
        while (true) {
            try {
                val frame = ws?.incoming?.receive() ?: break
                when (frame) {
                    is Frame.Text -> onWsText(frame.readText())
                    is Frame.Binary -> onWsBinary(frame.readBytes())
                    is Frame.Close -> {
                        log.devVerbose(TAG) { "Received Close frame" }
                        break
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                log.lifecycleError(TAG, "websocket receive error", e)
                log.devVerbose(TAG, "Receive error while reading websocket frame", e)
                break
            }
        }
        scope.launch { onClose() }
    }

    private fun onWsText(data: String) {
        log.devVerbose(TAG) { "onWsText: `$data`" }
        val packet = try {
            EngineIoCodec.decode(data)
        } catch (e: Exception) {
            onParseError(data, e)
            return
        }

        if (packet is EnginePacket.Open) {
            sessionId = packet.sid
        }

        if (packet is EnginePacket.Close) {
            scope.launch { onClose() }
            return
        }

        onPacket(packet)
    }

    private fun onWsBinary(data: ByteArray) {
        log.devVerbose(TAG) { "onWsBinary ${data.size} bytes — attachment frame" }
        events.emit(EVENT_BINARY, ByteString(data))
    }

    override fun doSend(messages: List<OutboundEngineMessage>) {
        log.devVerbose(TAG) { "doSend ${messages.size} messages start" }
        writable = false

        ioScope.launch {
            for (message in messages) {
                if (state != TransportState.OPEN) {
                    break
                }
                try {
                    val data = EngineIoCodec.encode(message.packet)
                    log.devVerbose(TAG) { "doSend: ${message.packet}, `$data`" }
                    ws?.send(Frame.Text(data))
                    for (attachment in message.binaryAttachments) {
                        ws?.send(Frame.Binary(true, attachment.toByteArray()))
                    }
                } catch (e: Exception) {
                    scope.launch { onError(e) }
                    return@launch
                }
            }

            scope.launch {
                log.devVerbose(TAG) { "doSend ${messages.size} messages finish" }
                writable = true
                val drainedCount = probeDrainCount(messages)
                events.emit(EVENT_DRAIN, drainedCount)
            }
        }
    }

    override fun doClose(fromOpenState: Boolean) {
        log.devVerbose(TAG) { "doClose fromOpenState=$fromOpenState" }
        ioScope.launch {
            try {
                ws?.close()
            } catch (e: Exception) {
                log.devVerbose(TAG, "ws close error", e)
            }
        }
    }

    fun uri(): String = uri(SECURE_SCHEMA, INSECURE_SCHEMA)

    private fun probeDrainCount(messages: List<OutboundEngineMessage>): Int {
        if (!isProbe) return messages.size
        val onlyProbePing = messages.size == 1 &&
            messages[0].packet is EnginePacket.Ping &&
            (messages[0].packet as EnginePacket.Ping).payload == PROBE
        return if (onlyProbePing) 0 else messages.size
    }

    private fun ByteString.toByteArray(): ByteArray = ByteArray(size) { index -> this[index] }

    companion object {
        const val NAME = "websocket"
        private const val SECURE_SCHEMA = "wss"
        private const val INSECURE_SCHEMA = "ws"
        private const val PROBE = "probe"
        private const val TAG = "WebSocketTransport"
    }
}
