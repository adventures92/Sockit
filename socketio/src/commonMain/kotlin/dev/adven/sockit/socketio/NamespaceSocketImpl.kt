package dev.adven.sockit.socketio

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.SocketEvent
import dev.adven.sockit.api.SocketException
import dev.adven.sockit.api.SocketOptions
import dev.adven.sockit.api.SocketPayload
import dev.adven.sockit.api.StreamCommand
import dev.adven.sockit.api.encodePayload
import dev.adven.sockit.api.toBufferOverflow
import dev.adven.sockit.internal.WorkQueue
import dev.adven.sockit.internal.logging.socketLog
import dev.adven.sockit.protocol.BinaryAssembler
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import dev.adven.sockit.protocol.SocketPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

internal class NamespaceSocketImpl(
    private val manager: ConnectionManager,
    private val namespace: String,
    private val options: SocketOptions,
    private val scope: CoroutineScope,
    private val workQueue: WorkQueue,
) : dev.adven.sockit.api.NamespaceSocket {
    private val log = options.socketLog
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _id = MutableStateFlow<String?>(null)
    override val id: StateFlow<String?> = _id.asStateFlow()

    private val _errors = MutableSharedFlow<SocketError>(extraBufferCapacity = 64)
    override val errors: Flow<SocketError> = _errors.asSharedFlow()

    private var active = false
    private var connected = false
    private val sendBuffer = ArrayList<OutboundEngineMessage>()
    private val recvBuffer = ArrayList<BufferedInboundEvent>()
    private var binaryAssembler: BinaryAssembler? = null
    private val ackCallbacks = mutableMapOf<Int, PendingAck>()
    private var nextAckId = 0
    private val eventFlows = mutableMapOf<String, MutableSharedFlow<SocketEvent>>()
    private val pendingEvents = mutableMapOf<String, ArrayDeque<SocketEvent>>()

    override fun isConnected(): Boolean = connectionState.value is ConnectionState.Connected

    override fun isDisconnected(): Boolean = connectionState.value is ConnectionState.Disconnected

    override fun open() {
        workQueue.launch {
            log.lifecycleInfo(TAG, "open namespace=$namespace")
            log.devVerbose(TAG) { "open: namespace=$namespace, connected=$connected, active=$active" }
            if (active && connected) return@launch
            active = true
            if (!connected) {
                updateState(ConnectionState.Connecting)
            }
            manager.onNamespaceOpen(this@NamespaceSocketImpl)
        }
    }

    override fun close() {
        workQueue.launch {
            log.lifecycleInfo(TAG, "close namespace=$namespace")
            log.devVerbose(TAG) { "close: namespace=$namespace, connected=$connected" }
            active = false
            if (connected) {
                manager.send(listOf(OutboundEngineMessage(EnginePacket.Message(SocketPacket.Disconnect(namespace)))))
            }
            onClose("client disconnect")
            manager.onNamespaceClosed(this@NamespaceSocketImpl)
        }
    }

    override fun emit(event: String, vararg payloads: Any?) {
        workQueue.launch {
            emitInternal(event, payloads.toList(), ack = null)
        }
    }

    override fun emit(command: StreamCommand) {
        emit(command.eventName, command.payload)
    }

    override suspend fun emitAwait(event: String, vararg payloads: Any?) {
        val deferred = CompletableDeferred<Unit>()
        workQueue.launch {
            try {
                emitInternal(event, payloads.toList(), ack = null)
                deferred.complete(Unit)
            } catch (e: SocketException.SendFailed) {
                deferred.completeExceptionally(e)
            }
        }
        deferred.await()
    }

    override suspend fun emitAwait(command: StreamCommand) {
        emitAwait(command.eventName, command.payload)
    }

    override fun emitWithAck(event: String, vararg payloads: Any?): Flow<SocketEvent> = flow {
        val ackData = CompletableDeferred<List<SocketPayload>>()
        workQueue.launch {
            val ackId = allocateAckId()
            val pending = PendingAck(
                onResult = { args -> if (!ackData.isCompleted) ackData.complete(args) },
                onFailure = { error -> if (!ackData.isCompleted) ackData.completeExceptionally(SocketException.SendFailed(error)) },
            )
            ackCallbacks[ackId] = pending
            try {
                emitInternal(event, payloads.toList(), ack = ackId)
            } catch (e: SocketException.SendFailed) {
                ackCallbacks.remove(ackId)
                ackData.completeExceptionally(e)
                return@launch
            }
            // Optional timeout so the flow can't hang on a live-but-unresponsive server;
            // disconnects are handled separately by clearAckCallbacks failing the pending ack.
            val timeoutMs = options.ackTimeoutMs
            if (timeoutMs > 0) {
                pending.timeoutJob = scope.launch {
                    delay(timeoutMs.milliseconds)
                    workQueue.launch {
                        ackCallbacks.remove(ackId)?.onFailure(SocketError.Timeout("ack"))
                    }
                }
            }
        }
        val args = ackData.await()
        emit(SocketEvent(event, args))
    }

    override fun events(name: String): Flow<SocketEvent> = callbackFlow {
        val flow = eventFlows.getOrPut(name) {
            MutableSharedFlow(
                extraBufferCapacity = options.eventBuffer.capacity,
                onBufferOverflow = options.eventBuffer.overflow.toBufferOverflow(),
            )
        }
        val job = scope.launch {
            flow.collect { event ->
                trySend(event)
            }
        }
        // Wait until that collector is actually attached before flushing anything buffered
        // pre-subscription — tryEmit into a zero-subscriber, replay=0 SharedFlow silently
        // discards the value, same as the original bug.
        flow.subscriptionCount.first { it > 0 }
        workQueue.launch {
            pendingEvents.remove(name)?.forEach { flow.tryEmit(it) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun openAwait() {
        if (isConnected()) return
        open()
        withTimeout((options.timeoutMs + 5_000L).milliseconds) {
            manager.awaitNamespaceState(this@NamespaceSocketImpl) { state ->
                state is ConnectionState.Connected || state is ConnectionState.Failed
            }
        }
        val state = connectionState.value
        if (state is ConnectionState.Failed) {
            throw SocketException.ConnectionFailed(state.error)
        }
    }

    internal fun wantsConnection(): Boolean = active

    internal fun sendConnectPacket() {
        workQueue.launch { sendConnectPacketOnWorker() }
    }

    /** Caller must already be on [workQueue] (via [ConnectionManager] engine callbacks). */
    internal fun onEngineOpen() {
        if (active && !connected) {
            sendConnectPacketOnWorker()
        }
    }

    /** Caller must already be on [workQueue]. */
    internal fun onEngineClose(reconnecting: Boolean, attempt: Int, cause: SocketError? = null) {
        if (!active) {
            if (!connected) {
                updateState(ConnectionState.Disconnected)
            }
            return
        }
        connected = false
        _id.value = null
        clearAckCallbacks()
        if (reconnecting && options.reconnection) {
            updateState(ConnectionState.Reconnecting(attempt))
        } else {
            // A close nothing will retry is Failed when an error caused it, and Disconnected only
            // when it was asked for. Collapsing both to Disconnected threw the cause away, leaving
            // a consumer unable to tell a deliberate close from a dropped connection.
            updateState(cause?.let { ConnectionState.Failed(it) } ?: ConnectionState.Disconnected)
        }
    }

    /** Caller must already be on [workQueue]. */
    internal fun onEngineDestroy() {
        active = false
        connected = false
        _id.value = null
        sendBuffer.clear()
        recvBuffer.clear()
        binaryAssembler = null
        clearAckCallbacks()
        updateState(ConnectionState.Disconnected)
    }

    /** Caller must already be on [workQueue]. */
    internal fun onSocketPacket(packet: SocketPacket) {
        if (packet.namespace != namespace) return
        when (packet) {
            is SocketPacket.Connect -> onConnect(packet.data)
            is SocketPacket.Disconnect -> onServerDisconnect()
            is SocketPacket.ConnectError -> onConnectError(packet.data)
            is SocketPacket.Event -> onEvent(packet.id, packet.data)
            is SocketPacket.Ack -> onAck(packet.id, packet.data)
            is SocketPacket.BinaryEvent -> startBinaryAssembly(packet)
            is SocketPacket.BinaryAck -> startBinaryAssembly(packet)
        }
    }

    /** Caller must already be on [workQueue]. */
    internal fun onBinaryData(data: ByteString) {
        binaryAssembler?.add(data)
    }

    private fun sendConnectPacketOnWorker() {
        if (!active || connected) return
        val authData = buildAuthData()
        val packet = EnginePacket.Message(SocketPacket.Connect(namespace, authData))
        manager.send(listOf(OutboundEngineMessage(packet)))
    }

    internal fun onEngineError(error: SocketError) {
        scope.launch {
            _errors.emit(error)
        }
    }

    private fun onConnect(data: String?) {
        val sid = data?.let { parseConnectSid(it) }.orEmpty()
        log.lifecycleInfo(TAG, "connected namespace=$namespace")
        log.devVerbose(TAG) { "onConnect sid=$sid namespace=$namespace" }
        connected = true
        _id.value = sid.takeIf { it.isNotEmpty() }
        updateState(ConnectionState.Connected)
        flushRecvBuffer()
        flushSendBuffer()
    }

    private fun onServerDisconnect() {
        log.lifecycleInfo(TAG, "server disconnect namespace=$namespace")
        active = false
        onClose("server disconnect")
        manager.onNamespaceClosed(this)
    }

    private fun onConnectError(data: String) {
        val error = parseConnectError(data)
        log.lifecycleError(TAG, error)
        log.devVerbose(TAG) { "onConnectError namespace=$namespace data=$data" }
        active = false
        connected = false
        _id.value = null
        updateState(ConnectionState.Failed(error))
        scope.launch { _errors.emit(error) }
        manager.onNamespaceError(error)
        manager.onNamespaceClosed(this)
    }

    private fun onClose(reason: String) {
        log.lifecycleInfo(TAG, "closed namespace=$namespace")
        log.devVerbose(TAG) { "onClose namespace=$namespace reason=$reason" }
        connected = false
        _id.value = null
        sendBuffer.clear()
        recvBuffer.clear()
        binaryAssembler = null
        clearAckCallbacks()
        if (active) {
            updateState(ConnectionState.Connecting)
        } else {
            updateState(ConnectionState.Disconnected)
        }
    }

    private fun onEvent(ackId: Int?, data: String, attachments: List<ByteString> = emptyList()) {
        if (connected) {
            dispatchEvent(data, ackId, attachments)
        } else {
            recvBuffer.add(BufferedInboundEvent(data, ackId, attachments))
        }
    }

    private fun onAck(ackId: Int?, data: String, attachments: List<ByteString> = emptyList()) {
        val id = ackId ?: return
        val pending = ackCallbacks.remove(id) ?: run {
            log.devVerbose(TAG) { "unexpected ack id=$id" }
            return
        }
        pending.timeoutJob?.cancel()
        val args = runCatching { parseAckArgs(data, attachments) }.getOrNull()
        if (args != null) {
            pending.onResult(args)
        } else {
            pending.onFailure(SocketError.ParseError(data))
        }
    }

    private fun startBinaryAssembly(packet: SocketPacket) {
        if (binaryAssembler != null) {
            log.lifecycleError(TAG, "binary assembly already in progress")
        }
        binaryAssembler = BinaryAssembler(packet) { isAck, ackId, data, attachments ->
            binaryAssembler = null
            if (isAck) {
                onAck(ackId, data, attachments)
            } else {
                onEvent(ackId, data, attachments)
            }
        }
        if (binaryAssembler?.isComplete() == true) {
            binaryAssembler = null
        }
    }

    private fun dispatchEvent(data: String, ackId: Int?, attachments: List<ByteString> = emptyList()) {
        val (eventName, args) = parseEventData(data, attachments) ?: return
        val socketEvent = SocketEvent(eventName, args)
        if (ackId != null) {
            socketEvent.ack = AckResponder(ackId)
        }
        val flow = eventFlows[eventName]
        if (flow != null && flow.subscriptionCount.value > 0) {
            flow.tryEmit(socketEvent)
        } else {
            // Buffer event for which no subscriber is currently attached — otherwise
            // eventFlows[eventName]?.tryEmit is a silent no-op into a zero-subscriber, replay=0
            // SharedFlow, permanently stranding any ack the server is waiting on. This covers both
            // "nobody has ever subscribed" and "the previous subscriber unsubscribed and nobody has
            // resubscribed yet". Flushed into the MutableSharedFlow the next time events() attaches a
            // live collector; capped like SocketOptions.eventBufferCapacity so a name nobody ever
            // subscribes to can't grow unbounded.
            val buffer = pendingEvents.getOrPut(eventName) { ArrayDeque() }
            buffer.addLast(socketEvent)
            while (buffer.size > options.eventBuffer.capacity) {
                buffer.removeFirstOrNull()
            }
        }
    }

    private fun flushRecvBuffer() {
        recvBuffer.toList().forEach { dispatchEvent(it.data, it.ackId, it.attachments) }
        recvBuffer.clear()
    }

    private fun flushSendBuffer() {
        if (sendBuffer.isEmpty()) return
        manager.send(sendBuffer.toList())
        sendBuffer.clear()
    }

    private fun emitInternal(event: String, payloads: List<Any?>, ack: Int?) {
        if (RESERVED_EVENTS.contains(event)) {
            val error = SocketError.SendFailed(IllegalArgumentException("emit reserved event: $event"))
            reportSendFailure(error)
            throw SocketException.SendFailed(error)
        }
        val packets = encodeEventMessages(event, payloads, ack)
        if (connected) {
            manager.send(packets)
        } else {
            sendBuffer.addAll(packets)
        }
    }

    private fun encodeEventMessages(event: String, payloads: List<Any?>, ackId: Int?): List<OutboundEngineMessage> {
        val (args, binaryAttachments) = encodeArgs(payloads)
        val data = buildJsonArray {
            add(JsonPrimitive(event))
            args.forEach { add(it) }
        }.toString()

        return if (binaryAttachments.isEmpty()) {
            listOf(
                OutboundEngineMessage(
                    EnginePacket.Message(SocketPacket.Event(namespace, data, ackId)),
                ),
            )
        } else {
            val socketPkt = SocketPacket.BinaryEvent(namespace, data, attachmentCount = binaryAttachments.size)
            listOf(OutboundEngineMessage(EnginePacket.Message(socketPkt), binaryAttachments))
        }
    }

    private fun encodeAckMessages(ackId: Int, payloads: List<Any?>): List<OutboundEngineMessage> {
        val (args, binaryAttachments) = encodeArgs(payloads)
        val data = buildJsonArray { args.forEach { add(it) } }.toString()

        return if (binaryAttachments.isEmpty()) {
            listOf(
                OutboundEngineMessage(
                    EnginePacket.Message(SocketPacket.Ack(namespace, ackId, data)),
                ),
            )
        } else {
            val socketPkt = SocketPacket.BinaryAck(namespace, ackId, data, attachmentCount = binaryAttachments.size)
            listOf(OutboundEngineMessage(EnginePacket.Message(socketPkt), binaryAttachments))
        }
    }

    /**
     * Encodes each payload in its original position: JSON values pass through as-is, binary
     * payloads become an inline `_placeholder` object (numbered by attachment order) so the
     * returned arg list can be serialized directly without losing interleaving between binary
     * and non-binary arguments.
     */
    private fun encodeArgs(payloads: List<Any?>): Pair<List<JsonElement>, List<ByteString>> {
        val binaryAttachments = mutableListOf<ByteString>()
        val args = payloads.map { payload ->
            when (val encoded = encodePayload(payload)) {
                is SocketPayload.Text -> JsonPrimitive(encoded.value)
                is SocketPayload.Json -> encoded.element
                is SocketPayload.Binary -> {
                    val index = binaryAttachments.size
                    binaryAttachments.add(encoded.bytes)
                    buildJsonObject {
                        put("_placeholder", JsonPrimitive(true))
                        put("num", JsonPrimitive(index))
                    }
                }
            }
        }
        return args to binaryAttachments
    }

    /**
     * Responder handed to consumers via [SocketEvent.ack]. Enqueues the reply onto
     * [workQueue] so it runs on the single worker, and sends the ACK at most once;
     * a reply for an already-closed namespace is dropped (the server is no longer waiting).
     */
    private inner class AckResponder(private val ackId: Int) : dev.adven.sockit.api.Ack {
        private var used = false

        override fun send(vararg payloads: Any?) {
            val args = payloads.toList()
            workQueue.launch {
                if (used) {
                    log.devVerbose(TAG) { "ack id=$ackId already sent — ignoring repeat" }
                    return@launch
                }
                used = true
                if (!connected) {
                    log.devVerbose(TAG) { "drop ack id=$ackId — namespace not connected" }
                    reportSendFailure(
                        SocketError.SendFailed(IllegalStateException("ack id=$ackId dropped: namespace not connected")),
                    )
                    return@launch
                }
                manager.send(encodeAckMessages(ackId, args))
            }
        }
    }

    private fun reportSendFailure(error: SocketError) {
        scope.launch { _errors.emit(error) }
        manager.onNamespaceError(error)
    }

    private fun updateState(state: ConnectionState) {
        _connectionState.value = state
        manager.onNamespaceStateChanged()
    }

    private fun allocateAckId(): Int = nextAckId++

    private fun clearAckCallbacks() {
        if (ackCallbacks.isEmpty()) return
        val pending = ackCallbacks.values.toList()
        ackCallbacks.clear()
        val error = SocketError.TransportClosed("connection closed before acknowledgement")
        pending.forEach {
            it.timeoutJob?.cancel()
            it.onFailure(error)
        }
    }

    private fun buildAuthData(): String? {
        if (options.auth.isEmpty()) return null
        return options.auth.toString()
    }

    companion object {
        private const val TAG = "NamespaceSocket"
        private val json = Json { ignoreUnknownKeys = true }
        private val RESERVED_EVENTS = setOf(
            "connect",
            "connect_error",
            "disconnect",
            "disconnecting",
            "newListener",
            "removeListener",
        )

        private fun parseConnectError(raw: String): SocketError.ConnectError {
            val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            return when (element) {
                is JsonObject -> SocketError.ConnectError(
                    message = element["message"]?.jsonPrimitive?.contentOrNull ?: raw,
                    data = element["data"]?.takeUnless { it is JsonNull }?.toString(),
                )

                is JsonPrimitive -> SocketError.ConnectError(element.contentOrNull ?: raw)
                else -> SocketError.ConnectError(raw)
            }
        }

        private fun parseConnectSid(data: String): String? {
            val element = runCatching { json.parseToJsonElement(data) }.getOrNull()
            if (element is JsonObject) {
                return element["sid"]?.jsonPrimitive?.content
            }
            return null
        }

        private fun parseEventData(data: String, attachments: List<ByteString>): Pair<String, List<SocketPayload>>? {
            val array = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonArray ?: return null
            if (array.isEmpty()) return null
            val eventName = array[0].jsonPrimitive.content
            val args = array.drop(1).map { toPayload(it, attachments) }
            return eventName to args
        }

        private fun parseAckArgs(data: String, attachments: List<ByteString>): List<SocketPayload> {
            val array = json.parseToJsonElement(data) as? JsonArray ?: return emptyList()
            return array.map { toPayload(it, attachments) }
        }

        /**
         * Maps one inbound arg to a payload. A top-level binary placeholder becomes real bytes
         * ([SocketPayload.Binary]); anything else stays JSON, with any nested placeholders
         * hex-substituted (rare — top-level binary is the common case).
         */
        private fun toPayload(element: JsonElement, attachments: List<ByteString>): SocketPayload {
            val index = binaryPlaceholderIndex(element)
            return if (index != null && index in attachments.indices) {
                SocketPayload.Binary(attachments[index])
            } else {
                SocketPayload.Json(BinaryAssembler.reassembleElement(element, attachments))
            }
        }

        private fun binaryPlaceholderIndex(element: JsonElement): Int? {
            if (element !is JsonObject) return null
            if (element["_placeholder"]?.jsonPrimitive?.content != "true") return null
            return element["num"]?.jsonPrimitive?.content?.toIntOrNull()
        }
    }
}

/** An inbound EVENT held until the namespace is connected, preserving any server ack id and binary attachments. */
private data class BufferedInboundEvent(
    val data: String,
    val ackId: Int?,
    val attachments: List<ByteString> = emptyList(),
)

/** A client-initiated [NamespaceSocket.emitWithAck] awaiting the server's ACK args, or a failure. */
private class PendingAck(
    val onResult: (List<SocketPayload>) -> Unit,
    val onFailure: (SocketError) -> Unit,
) {
    var timeoutJob: Job? = null
}
