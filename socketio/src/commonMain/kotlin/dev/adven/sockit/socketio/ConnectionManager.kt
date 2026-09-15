package dev.adven.sockit.socketio

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.SocketOptions
import dev.adven.sockit.engineio.EngineConnection
import dev.adven.sockit.engineio.parseEngineUrl
import dev.adven.sockit.internal.EventBus
import dev.adven.sockit.internal.SubscriptionHandle
import dev.adven.sockit.internal.WorkQueue
import dev.adven.sockit.internal.logging.socketLog
import dev.adven.sockit.protocol.OutboundEngineMessage
import dev.adven.sockit.protocol.SocketPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString

internal class ConnectionManager(
    val url: String,
    val options: SocketOptions,
) {
    private val log = options.socketLog
    private val job = SupervisorJob()
    private val parentScope = CoroutineScope(job + Dispatchers.Default)
    private val workQueue = WorkQueue()
    private val engine = EngineConnection(url, options, parentScope, workQueue)
    private val urlNamespace: String? = parseEngineUrl(url).path.takeIf { it.isNotEmpty() && it != "/" }
    private val namespaces = linkedMapOf<String, NamespaceSocketImpl>()
    private val engineSubscriptions = mutableListOf<SubscriptionHandle>()
    private var engineBound = false
    private var engineOpen = false
    private var reconnectAttempt = 0
    private var clientRefCount = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errors = MutableSharedFlow<SocketError>(extraBufferCapacity = 64)
    val errors: SharedFlow<SocketError> = _errors.asSharedFlow()

    fun acquireClient() {
        clientRefCount++
    }

    fun releaseClient(): Boolean {
        clientRefCount--
        if (clientRefCount <= 0) {
            return true
        }
        return false
    }

    fun destroyFromRegistry() {
        destroy()
    }

    suspend fun destroyAwait() {
        val done = CompletableDeferred<Unit>()
        workQueue.launch {
            destroyOnWorker()
            done.complete(Unit)
        }
        done.await()
    }

    /** A caller-supplied [path] of exactly `"/"` (the default) defers to [urlNamespace] when the
     *  connect URL had one; an explicit non-root [path] always wins over the URL. */
    fun namespace(path: String): NamespaceSocketImpl {
        val resolvedPath = if (path == "/") urlNamespace ?: path else path
        val normalized = normalizeNamespace(resolvedPath)
        return namespaces.getOrPut(normalized) {
            NamespaceSocketImpl(
                manager = this,
                namespace = normalized,
                options = options,
                scope = parentScope,
                workQueue = workQueue,
            )
        }
    }

    /** Caller must already be on [workQueue] — avoids reordering with a subsequent [NamespaceSocketImpl.open]. */
    fun onNamespaceOpen(socket: NamespaceSocketImpl) {
        ensureEngineBound()
        if (engineOpen) {
            socket.onEngineOpen()
        } else {
            engine.openOnWorker()
        }
    }

    /** Caller must already be on [workQueue]. */
    @Suppress("UnusedParameter")
    fun onNamespaceClosed(socket: NamespaceSocketImpl) {
        if (namespaces.values.none { it.wantsConnection() }) {
            engineOpen = false
            engine.closeOnWorker()
        }
    }

    /** Caller must already be on [workQueue]. */
    fun onNamespaceStateChanged() {
        updateAggregateState()
    }

    fun send(messages: List<OutboundEngineMessage>) {
        engine.sendMessages(messages)
    }

    fun sendPackets(packets: List<dev.adven.sockit.protocol.EnginePacket>) {
        engine.sendPackets(packets)
    }

    fun pauseReconnect() {
        workQueue.launch {
            engine.pauseReconnectOnWorker()
        }
    }

    fun resumeReconnect() {
        workQueue.launch {
            engine.resumeReconnectOnWorker()
            if (namespaces.values.none { it.wantsConnection() }) {
                return@launch
            }
            ensureEngineBound()
            if (!engineOpen) {
                engine.openOnWorker()
            }
        }
    }

    suspend fun awaitNamespaceState(
        socket: NamespaceSocketImpl,
        predicate: (ConnectionState) -> Boolean,
    ) {
        val current = socket.connectionState.value
        if (predicate(current)) return
        val deferred = CompletableDeferred<Unit>()
        val job = parentScope.launch {
            socket.connectionState.collect { state ->
                if (predicate(state)) {
                    deferred.complete(Unit)
                }
            }
        }
        try {
            deferred.await()
        } finally {
            job.cancel()
        }
    }

    private fun ensureEngineBound() {
        if (engineBound) return
        engineBound = true
        bindEngineEvents()
    }

    private fun bindEngineEvents() {
        // Engine emits on the shared [workQueue]; handle inline to avoid stale close/open reordering.
        engineSubscriptions += engine.events.on(
            EngineConnection.EVENT_OPEN,
            EventBus.Listener {
                onEngineOpen()
            },
        )
        engineSubscriptions += engine.events.on(
            EngineConnection.EVENT_CLOSE,
            EventBus.Listener { args ->
                val reason = args.firstOrNull()?.toString().orEmpty()
                onEngineClose(reason)
            },
        )
        engineSubscriptions += engine.events.on(
            EngineConnection.EVENT_DATA,
            EventBus.Listener { args ->
                val packet = args.firstOrNull() as? SocketPacket ?: return@Listener
                namespaces.values.forEach { it.onSocketPacket(packet) }
            },
        )
        engineSubscriptions += engine.events.on(
            EngineConnection.EVENT_ERROR,
            EventBus.Listener { args ->
                val error = args.firstOrNull() as? SocketError ?: return@Listener
                onEngineError(error)
            },
        )
        engineSubscriptions += engine.events.on(
            EngineConnection.EVENT_BINARY_DATA,
            EventBus.Listener { args ->
                val data = args.firstOrNull() as? ByteString ?: return@Listener
                namespaces.values.forEach { it.onBinaryData(data) }
            },
        )
    }

    private fun onEngineOpen() {
        log.lifecycleInfo(TAG, "engine connected")
        engineOpen = true
        reconnectAttempt = 0
        _connectionState.value = ConnectionState.Connecting
        namespaces.values.forEach { it.onEngineOpen() }
        updateAggregateState()
    }

    private fun onEngineClose(reason: String) {
        log.lifecycleInfo(TAG, "engine disconnected")
        log.devVerbose(TAG) { "onEngineClose reason=$reason" }
        engineOpen = false
        val reconnecting = options.reconnection && reason != "force close"
        if (reconnecting) {
            reconnectAttempt++
        } else {
            reconnectAttempt = 0
        }
        namespaces.values.forEach { it.onEngineClose(reconnecting, reconnectAttempt) }
        updateAggregateState()
    }

    private fun onEngineError(error: SocketError) {
        parentScope.launch { _errors.emit(error) }
        namespaces.values.forEach { it.onEngineError(error) }
        if (error is SocketError.PingTimeout || error is SocketError.TlsFailure) {
            _connectionState.value = ConnectionState.Failed(error)
        }
    }

    internal fun onNamespaceError(error: SocketError) {
        parentScope.launch { _errors.emit(error) }
    }

    private fun updateAggregateState() {
        val states = namespaces.values.map { it.connectionState.value }
        _connectionState.value = when {
            states.any { it is ConnectionState.Connected } -> ConnectionState.Connected
            states.any { it is ConnectionState.Reconnecting } ->
                states.filterIsInstance<ConnectionState.Reconnecting>().firstOrNull()
                    ?: ConnectionState.Reconnecting(reconnectAttempt)

            states.any { it is ConnectionState.Failed } ->
                states.filterIsInstance<ConnectionState.Failed>().first()

            states.any { it is ConnectionState.Connecting } -> ConnectionState.Connecting
            engineOpen -> ConnectionState.Connecting
            else -> ConnectionState.Disconnected
        }
    }

    private fun destroy() {
        workQueue.launch { destroyOnWorker() }
    }

    private fun destroyOnWorker() {
        log.devVerbose(TAG) { "destroy" }
        engineSubscriptions.forEach { it.destroy() }
        engineSubscriptions.clear()
        engineOpen = false
        namespaces.values.forEach { it.onEngineDestroy() }
        namespaces.clear()
        engine.closeOnWorker()
        reconnectAttempt = 0
        _connectionState.value = ConnectionState.Disconnected
        job.cancel()
    }

    companion object {
        private const val TAG = "ConnectionManager"

        fun originKey(url: String): String {
            val parsed = parseEngineUrl(url)
            val scheme = if (parsed.secure) "https" else "http"
            return "$scheme://${parsed.hostname}:${parsed.port}"
        }

        fun normalizeNamespace(path: String): String = when {
            path.isEmpty() -> "/"
            path.startsWith("/") -> path
            else -> "/$path"
        }
    }
}
