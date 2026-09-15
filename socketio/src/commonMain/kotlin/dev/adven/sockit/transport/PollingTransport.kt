package dev.adven.sockit.transport

import dev.adven.sockit.internal.EventBus
import dev.adven.sockit.internal.logging.SocketLog
import dev.adven.sockit.protocol.EngineIoCodec
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class PollingTransport(
    options: TransportOptions,
    httpClient: HttpClient,
    log: SocketLog,
    scope: CoroutineScope,
    ioScope: CoroutineScope = scope,
) : Transport(options, httpClient, log, scope, ioScope, NAME) {
    private var polling = false
    private var pausePending = false

    override fun pause(onPause: () -> Unit) {
        log.devVerbose(TAG) { "pause" }
        state = TransportState.PAUSED
        pausePending = true
        val paused = {
            if (pausePending) {
                pausePending = false
                log.devVerbose(TAG) { "paused" }
                state = TransportState.PAUSED
                onPause()
            }
        }

        if (polling || !writable) {
            var counter = 0
            val waitJob: (String, String) -> Unit = { event, job ->
                log.devVerbose(TAG) { "pause: wait $job" }
                counter++
                events.once(
                    event,
                    EventBus.Listener {
                        log.devVerbose(TAG) { "pause: pre-pause $job complete" }
                        counter--
                        if (counter == 0) {
                            paused()
                        }
                    },
                )
            }

            if (polling) {
                waitJob(EVENT_POLL_COMPLETE, "polling")
            }
            if (!writable) {
                waitJob(EVENT_DRAIN, "writing")
            }
        } else {
            paused()
        }
    }

    override fun resume() {
        if (state != TransportState.PAUSED) return
        log.devVerbose(TAG) { "resume" }
        pausePending = false
        state = TransportState.OPEN
        if (!polling) {
            poll()
        }
    }

    override fun doOpen() {
        poll()
    }

    private fun poll() {
        log.devVerbose(TAG) { "poll start" }
        polling = true

        val method = HttpMethod.Get
        val headers = prepareRequestHeaders(method)
        ioScope.launch {
            doRequest(uri(), method, headers, onResponse = { onPollComplete(it) })
        }
        events.emit(EVENT_POLL)
    }

    private fun prepareRequestHeaders(method: HttpMethod): Map<String, List<String>> {
        val requestHeaders = LinkedHashMap<String, List<String>>()
        requestHeaders.putAll(options.extraHeaders)
        if (method == HttpMethod.Post) {
            requestHeaders["Content-type"] = listOf("text/plain;charset=UTF-8")
        }
        requestHeaders["Accept"] = listOf("*/*")
        events.emit(EVENT_REQUEST_HEADERS, requestHeaders)
        return requestHeaders
    }

    private suspend fun doRequest(
        requestUri: String,
        method: HttpMethod,
        requestHeaders: Map<String, List<String>>,
        data: String? = null,
        onResponse: (String) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        log.devVerbose(TAG) { "doRequest ${method.value} $requestUri" }
        val response = try {
            httpClient.request(requestUri) {
                this.method = method
                headers {
                    putHeaders(this, requestHeaders)
                }
                if (data != null) {
                    setBody(data)
                }
            }
        } catch (e: Exception) {
            scope.launch { onError(e) }
            return
        }

        handleResponse(response, onResponse, onSuccess)
    }

    private suspend fun handleResponse(
        response: HttpResponse,
        onResponse: (String) -> Unit,
        onSuccess: () -> Unit,
    ) {
        log.devVerbose(TAG) { "doRequest response: ${response.status}" }
        scope.launch {
            events.emit(
                EVENT_RESPONSE_HEADERS,
                response.headers.entries().associate { entry ->
                    entry.key to entry.value
                },
            )
        }

        if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            scope.launch {
                onResponse(body)
                onSuccess()
            }
        } else {
            scope.launch {
                onError("HTTP error: ${response.status}")
            }
        }
    }

    private fun onPollComplete(data: String) {
        processIncomingPackets(data)
        if (state != TransportState.CLOSED) {
            polling = false
            events.emit(EVENT_POLL_COMPLETE)

            if (state == TransportState.OPEN) {
                poll()
            } else {
                log.devVerbose(TAG) { "onPollComplete ignore poll, state=$state" }
            }
        }
    }

    private fun processIncomingPackets(data: String) {
        log.devVerbose(TAG) { "processIncomingPackets: state=$state" }
        if (data.isEmpty()) return

        val records = if (data.contains(POLLING_RECORD_SEPARATOR)) {
            data.split(POLLING_RECORD_SEPARATOR).filter { it.isNotEmpty() }
        } else {
            listOf(data)
        }

        for (record in records) {
            if (record.startsWith(POLLING_BINARY_PREFIX)) {
                events.emit(EVENT_BINARY, decodePollingBinaryRecord(record))
                continue
            }

            val packet = try {
                EngineIoCodec.decode(record)
            } catch (e: Exception) {
                onParseError(record, e)
                return
            }

            if ((state == TransportState.OPENING || state == TransportState.CLOSING) &&
                packet is EnginePacket.Open
            ) {
                sessionId = packet.sid
                onOpen()
            }

            if (packet is EnginePacket.Close) {
                onClose()
                break
            }

            onPacket(packet)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodePollingBinaryRecord(record: String): ByteString {
        val bytes = Base64.decode(record.substring(POLLING_BINARY_PREFIX.length))
        return ByteString(bytes)
    }

    override fun doSend(messages: List<OutboundEngineMessage>) {
        writable = false
        val data = EngineIoCodec.encodePollingBatch(messages)

        val method = HttpMethod.Post
        val headers = prepareRequestHeaders(method)
        ioScope.launch {
            doRequest(
                requestUri = uri(),
                method = method,
                requestHeaders = headers,
                data = data,
                onResponse = { body ->
                    if (body.isNotEmpty()) {
                        processIncomingPackets(body)
                    }
                },
                onSuccess = {
                    writable = true
                    events.emit(EVENT_DRAIN, messages.size)
                },
            )
        }
    }

    override fun doClose(fromOpenState: Boolean) {
        val doCloseAction: () -> Unit = {
            log.devVerbose(TAG) { "doClose writing close packet" }
            events.once(
                EVENT_DRAIN,
                EventBus.Listener {
                    onClose()
                },
            )
            doSend(listOf(OutboundEngineMessage(EnginePacket.Close)))
        }

        if (fromOpenState) {
            log.devVerbose(TAG) { "doClose on OPEN state" }
            doCloseAction()
        } else {
            log.devVerbose(TAG) { "doClose on OPENING state, deferring close" }
            events.once(
                EVENT_OPEN,
                EventBus.Listener {
                    doCloseAction()
                },
            )
        }
    }

    fun uri(): String = uri(SECURE_SCHEMA, INSECURE_SCHEMA)

    companion object {
        const val NAME = "polling"
        private const val SECURE_SCHEMA = "https"
        private const val INSECURE_SCHEMA = "http"
        private const val TAG = "PollingTransport"
        private const val POLLING_RECORD_SEPARATOR = "\u001e"
        private const val POLLING_BINARY_PREFIX = "b"
    }
}
