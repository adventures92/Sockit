package dev.adven.sockit.engineio

import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.SocketOptions
import dev.adven.sockit.connection.ReconnectPolicy
import dev.adven.sockit.internal.EventBus
import dev.adven.sockit.internal.SubscriptionHandle
import dev.adven.sockit.internal.WorkQueue
import dev.adven.sockit.internal.logging.socketLog
import dev.adven.sockit.protocol.EngineIoCodec
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import dev.adven.sockit.protocol.ProtocolParseException
import dev.adven.sockit.transport.DefaultHttpClientFactory
import dev.adven.sockit.transport.DefaultTransportFactory
import dev.adven.sockit.transport.HttpClientFactory
import dev.adven.sockit.transport.PollingTransport
import dev.adven.sockit.transport.Transport
import dev.adven.sockit.transport.TransportFactory
import dev.adven.sockit.transport.buildTransportOptions
import io.ktor.client.HttpClient
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlin.time.Duration.Companion.milliseconds

internal enum class EngineState {
    INIT,
    OPENING,
    OPEN,
    CLOSING,
    CLOSED,
}

internal data class ParsedEngineUrl(
    val secure: Boolean,
    val hostname: String,
    val port: Int,
    val query: Map<String, String>,
    val path: String,
)

internal fun parseEngineUrl(url: String): ParsedEngineUrl {
    val parsed = Url(url)
    val secure = parsed.protocol == URLProtocol.HTTPS || parsed.protocol == URLProtocol.WSS
    var hostname = parsed.host
    if (hostname.count { it == ':' } > 1) {
        val start = hostname.indexOf('[')
        if (start != -1) {
            hostname = hostname.substring(start + 1)
        }
        val end = hostname.lastIndexOf(']')
        if (end != -1) {
            hostname = hostname.substring(0, end)
        }
    }
    val port = when {
        parsed.port > 0 -> parsed.port
        secure -> 443
        else -> 80
    }
    val query = buildMap {
        parsed.parameters.entries().forEach { entry ->
            entry.value.firstOrNull()?.let { put(entry.key, it) }
        }
    }
    return ParsedEngineUrl(
        secure = secure,
        hostname = hostname,
        port = port,
        query = query,
        path = parsed.encodedPath,
    )
}

internal class EngineConnection(
    url: String,
    private val options: SocketOptions,
    private val scope: CoroutineScope,
    private val workQueue: WorkQueue = WorkQueue(),
    private val transportFactory: TransportFactory = DefaultTransportFactory,
    private val httpClientFactory: HttpClientFactory = DefaultHttpClientFactory,
) {
    private val log = options.socketLog
    private val parsedUrl = parseEngineUrl(url)
    private val httpClient: HttpClient = httpClientFactory.create(options)
    private val reconnectPolicy = ReconnectPolicy(
        minMs = options.reconnectionDelayMs,
        maxMs = options.reconnectionDelayMaxMs,
        jitter = options.randomizationFactor,
        maxAttempts = options.reconnectionAttempts,
    )

    val events = EventBus()
    private val _errors = MutableSharedFlow<SocketError>(extraBufferCapacity = 64)
    val errors: SharedFlow<SocketError> = _errors.asSharedFlow()

    private var state = EngineState.INIT
    private var sessionId = ""
    private var upgrades = emptyList<String>()
    private var pingInterval = 0
    private var pingTimeout = 0
    private var maxPayload = 0
    private var transport: Transport? = null
    private val transportSubscriptions = mutableListOf<SubscriptionHandle>()
    private val upgradeController = UpgradeController()
    private var probeTransport: Transport? = null
    private val probeSubscriptions = mutableListOf<SubscriptionHandle>()
    private var probeFailed = false
    private var intentionalClose = false
    private var openPending = false

    val writeBuffer = ArrayDeque<OutboundEngineMessage>()
    private var prevBufferLen = 0
    private var pingTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectPaused = false

    fun open() {
        workQueue.launch { openOnWorker() }
    }

    /** Must run on [workQueue] — avoids reordering with [closeOnWorker] when called from [ConnectionManager]. */
    internal fun openOnWorker() {
        log.devVerbose(TAG) { "open: state=$state" }
        when (state) {
            EngineState.OPEN -> {
                events.emit(EVENT_OPEN)
                return
            }

            EngineState.CLOSING -> {
                openPending = true
                return
            }

            EngineState.INIT, EngineState.CLOSED -> Unit

            else -> {
                log.devVerbose(TAG) { "open at wrong state: $state" }
                return
            }
        }
        openPending = false
        intentionalClose = false
        reconnectPolicy.reset()
        reconnectJob?.cancel()
        val transportName = options.transports.first()
        state = EngineState.OPENING
        setTransport(createTransport(transportName))
        transport?.open()
    }

    fun close() {
        workQueue.launch { closeOnWorker() }
    }

    /** Must run on [workQueue]. */
    internal fun pauseReconnectOnWorker() {
        reconnectPaused = true
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Must run on [workQueue]. Clears the pause gate only — caller decides whether to [openOnWorker]. */
    internal fun resumeReconnectOnWorker() {
        reconnectPaused = false
    }

    internal fun isReconnectPaused(): Boolean = reconnectPaused

    /** Must run on [workQueue]. */
    internal fun closeOnWorker() {
        log.devVerbose(TAG) {
            "close: state=$state, writeBuffer.size=${writeBuffer.size}, upgrade=${upgradeController.isActive()}"
        }
        if (state != EngineState.OPENING && state != EngineState.OPEN) {
            return
        }
        intentionalClose = true
        openPending = false
        state = EngineState.CLOSING
        reconnectJob?.cancel()

        val closeAction = EventBus.Listener {
            log.devVerbose(TAG) { "socket closing - telling transport to close" }
            finishClose("force close", scheduleReconnect = false)
        }

        when {
            upgradeController.isActive() -> {
                abortProbeForClose()
                closeAction.call()
            }

            writeBuffer.isNotEmpty() -> {
                // Force close must not wait for drain — upgrade pause / mid-handshake can deadlock.
                writeBuffer.clear()
                prevBufferLen = 0
                closeAction.call()
            }

            else -> closeAction.call()
        }
    }

    fun send(packet: EnginePacket) {
        sendMessages(listOf(OutboundEngineMessage(packet)))
    }

    fun sendMessages(messages: List<OutboundEngineMessage>) {
        workQueue.launch {
            if (state != EngineState.OPENING && state != EngineState.OPEN) {
                log.devVerbose(TAG) { "sendMessages at wrong state: $state" }
                return@launch
            }
            writeBuffer.addAll(messages)
            flush()
        }
    }

    fun sendPackets(packets: List<EnginePacket>) {
        sendMessages(packets.map { OutboundEngineMessage(it) })
    }

    private fun createTransport(
        name: String,
        sid: String? = sessionId.takeIf { it.isNotEmpty() },
        isProbe: Boolean = false,
    ): Transport {
        val transportOptions = buildTransportOptions(
            socketOptions = options,
            hostname = parsedUrl.hostname,
            port = parsedUrl.port,
            secure = parsedUrl.secure,
            transportName = name,
            sid = sid,
            extraQuery = parsedUrl.query,
        )
        return transportFactory.create(
            name = name,
            options = transportOptions,
            httpClient = httpClient,
            scope = scope,
            ioScope = scope,
            log = log,
            isProbe = isProbe,
        )
    }

    private fun setTransport(newTransport: Transport) {
        log.lifecycleInfo(TAG, "transport=${newTransport.name}")
        log.devVerbose(TAG) { "setTransport ${newTransport.name}" }
        clearTransportSubscriptions()
        transport = newTransport
        bindTransport(newTransport, transportSubscriptions)
    }

    private fun bindTransport(target: Transport, subscriptions: MutableList<SubscriptionHandle>) {
        subscriptions += target.events.on(
            Transport.EVENT_DRAIN,
            EventBus.Listener { args ->
                val count = args.firstOrNull() as? Int ?: return@Listener
                onDrain(target, count)
            },
        )
        subscriptions += target.events.on(
            Transport.EVENT_PACKET,
            EventBus.Listener { args ->
                val packet = args.firstOrNull() as? EnginePacket ?: return@Listener
                onPacket(packet)
            },
        )
        subscriptions += target.events.on(
            Transport.EVENT_ERROR,
            EventBus.Listener { args ->
                onTransportError(args.firstOrNull())
            },
        )
        subscriptions += target.events.on(
            Transport.EVENT_CLOSE,
            EventBus.Listener {
                onClose("transport close")
            },
        )
        subscriptions += target.events.on(
            Transport.EVENT_BINARY,
            EventBus.Listener { args ->
                val data = args.firstOrNull() as? ByteString ?: return@Listener
                workQueue.launch {
                    events.emit(EVENT_BINARY_DATA, data)
                }
            },
        )
    }

    private fun clearTransportSubscriptions() {
        transportSubscriptions.forEach { it.destroy() }
        transportSubscriptions.clear()
    }

    private fun clearProbeSubscriptions() {
        probeSubscriptions.forEach { it.destroy() }
        probeSubscriptions.clear()
    }

    private fun onDrain(transport: Transport, count: Int) {
        workQueue.launch {
            log.devVerbose(TAG) {
                "onDrain: transport=${transport.name}, probe=${transport.isProbe}, count=$count, " +
                    "writeBuffer.size=${writeBuffer.size}, prevBufferLen=$prevBufferLen"
            }

            if (transport.isProbe) {
                if (count == 0) {
                    return@launch
                }
                if (upgradeController.isActive()) {
                    upgradeController.onUpgradeDrained()
                    maybeCompleteUpgrade()
                }
                return@launch
            }

            val removed = drainWriteBuffer(writeBuffer, count, log)
            if (removed == 0) {
                return@launch
            }
            prevBufferLen = (prevBufferLen - removed).coerceAtLeast(0)

            if (writeBuffer.isEmpty()) {
                events.emit(EVENT_DRAIN)
            } else if (writeBuffer.size > prevBufferLen) {
                flush()
            }
        }
    }

    private fun onPacket(packet: EnginePacket) {
        workQueue.launch {
            if (inactive()) {
                log.devVerbose(TAG) { "packet received at wrong state: $state" }
                return@launch
            }

            events.emit(EVENT_PACKET, packet)

            when (packet) {
                is EnginePacket.Open -> onHandshake(packet)
                is EnginePacket.Ping -> {
                    if (packet.payload != PROBE) {
                        events.emit(EVENT_PING)
                        sendPackets(listOf(EnginePacket.Pong()))
                        scheduleServerPingWatchdog()
                    }
                }

                is EnginePacket.Message -> events.emit(EVENT_DATA, packet.socket)
                else -> Unit
            }
        }
    }

    private fun onHandshake(open: EnginePacket.Open) {
        events.emit(EVENT_HANDSHAKE, open)
        sessionId = open.sid
        upgrades = filterUpgrades(open.upgrades)
        pingInterval = open.pingInterval
        pingTimeout = open.pingTimeout
        maxPayload = open.maxPayload
        onEngineOpen()

        if (state != EngineState.OPEN) {
            return
        }

        startHeartbeat()
    }

    private fun onEngineOpen() {
        log.lifecycleInfo(TAG, "connected")
        log.devVerbose(TAG) { "onOpen" }
        state = EngineState.OPEN
        events.emit(EVENT_OPEN)

        if (options.upgrade &&
            transport?.name == PollingTransport.NAME &&
            upgrades.isNotEmpty()
        ) {
            for (upgradeName in upgrades) {
                probeTransport(upgradeName)
            }
        }
    }

    private fun filterUpgrades(available: List<String>): List<String> = available.filter { name ->
        options.transports.contains(name) && name != transport?.name
    }

    private fun flush() {
        val currentTransport = transport
        val upgrading = upgradeController.phase == UpgradePhase.PAUSING_POLL ||
            upgradeController.phase == UpgradePhase.UPGRADING
        log.devVerbose(TAG) {
            "flush: state=$state, upgrading=$upgrading, prevBufferLen=$prevBufferLen, " +
                "writeBuffer.size=${writeBuffer.size}"
        }
        if (state == EngineState.CLOSED || currentTransport == null || upgrading) {
            return
        }

        dropOversizedLeadingMessage(currentTransport)

        if (writeBuffer.size > prevBufferLen) {
            val pending = writeBuffer.subList(prevBufferLen, writeBuffer.size).toList()
            val messages = limitPollingPayload(currentTransport, pending)
            prevBufferLen += messages.size
            currentTransport.send(messages)
            events.emit(EVENT_FLUSH, messages.size)
        }
    }

    /**
     * Drops (and reports) every leading buffered message that alone exceeds the polling
     * transport's maxPayload. Sending any of them would only produce a doomed oversized POST the
     * server rejects, and leaving one in [writeBuffer] would retry the exact same message on
     * every future flush since nothing else ever advances past it. Loops rather than checking
     * just the head once, because a single [sendMessages] call can batch two or more oversized
     * messages back-to-back (e.g. [dev.adven.sockit.socketio.NamespaceSocketImpl]'s pre-connect
     * send buffer flush) — without the loop, only the first would be dropped and
     * [EngineIoCodec.pollingBatchFit]'s "always fit at least one" rule would force-include the
     * second (now-leading) oversized message into this same outgoing batch.
     */
    private fun dropOversizedLeadingMessage(currentTransport: Transport) {
        if (currentTransport.name != PollingTransport.NAME || maxPayload <= 0) return
        while (writeBuffer.size > prevBufferLen &&
            EngineIoCodec.exceedsMaxPayload(writeBuffer[prevBufferLen], maxPayload)
        ) {
            writeBuffer.removeAt(prevBufferLen)
            log.lifecycleError(TAG, SocketError.SendFailed(null))
            emitError(
                SocketError.SendFailed(
                    IllegalStateException("message exceeds server maxPayload=$maxPayload bytes; dropped without sending"),
                ),
            )
        }
    }

    /**
     * Caps a single HTTP long-polling payload to the server-advertised `maxPayload` (Engine.IO v4).
     * Any remainder stays buffered and is flushed by [onDrain] after the current POST drains.
     * WebSocket frames are unbounded here, so [pending] is returned unchanged.
     */
    private fun limitPollingPayload(
        currentTransport: Transport,
        pending: List<OutboundEngineMessage>,
    ): List<OutboundEngineMessage> {
        if (currentTransport.name != PollingTransport.NAME || maxPayload <= 0) return pending
        val fit = EngineIoCodec.pollingBatchFit(pending, maxPayload)
        if (fit >= pending.size) return pending
        log.devVerbose(TAG) { "polling payload capped to $fit/${pending.size} messages (maxPayload=$maxPayload)" }
        return pending.subList(0, fit)
    }

    private fun probeTransport(name: String) {
        log.devVerbose(TAG) { "probing transport '$name'" }
        val probe = createTransport(name, sid = sessionId, isProbe = true)
        probeTransport = probe
        probeFailed = false
        upgradeController.startProbing()

        val onProbeOpen = EventBus.Listener {
            if (probeFailed || inactive()) return@Listener
            log.devVerbose(TAG) { "probe transport $name opened" }
            probe.send(listOf(OutboundEngineMessage(EnginePacket.Ping(PROBE))))
        }

        val onProbePacket = EventBus.Listener { args ->
            val packet = args.firstOrNull() as? EnginePacket ?: return@Listener
            handleProbePacket(name, probe, packet)
        }

        val onProbeError = EventBus.Listener {
            onProbeFailed(name, it.firstOrNull()?.toString() ?: "probe error")
        }

        val onProbeClose = EventBus.Listener {
            onProbeFailed(name, "probe transport closed")
        }

        val onSocketClose = EventBus.Listener {
            onProbeFailed(name, "socket closed during probe")
        }

        val onOtherUpgrade = EventBus.Listener { args ->
            val winner = args.firstOrNull() as? Transport ?: return@Listener
            if (winner.name != probe.name) {
                onProbeFailed(name, "another transport won upgrade: ${winner.name}")
            }
        }

        probeSubscriptions += probe.events.once(Transport.EVENT_OPEN, onProbeOpen)
        probeSubscriptions += probe.events.once(Transport.EVENT_PACKET, onProbePacket)
        probeSubscriptions += probe.events.on(
            Transport.EVENT_DRAIN,
            EventBus.Listener { args ->
                val count = args.firstOrNull() as? Int ?: return@Listener
                onDrain(probe, count)
            },
        )
        probeSubscriptions += probe.events.once(Transport.EVENT_ERROR, onProbeError)
        probeSubscriptions += probe.events.once(Transport.EVENT_CLOSE, onProbeClose)
        probeSubscriptions += events.once(EVENT_CLOSE, onSocketClose)
        probeSubscriptions += events.once(EVENT_UPGRADING, onOtherUpgrade)

        probe.open()
    }

    private fun handleProbePacket(name: String, probe: Transport, packet: EnginePacket) {
        workQueue.launch {
            if (probeFailed || inactive()) return@launch
            if (packet is EnginePacket.Pong && packet.payload == PROBE) {
                log.devVerbose(TAG) { "probe transport $name pong" }
                upgradeController.onProbePong()
                val polling = transport ?: return@launch
                polling.pause {
                    workQueue.launch {
                        if (probeFailed || inactive()) return@launch
                        upgradeController.onPollingPaused()
                        probe.send(listOf(OutboundEngineMessage(EnginePacket.Upgrade)))
                    }
                }
            } else {
                onProbeFailed(name, "unexpected probe response: $packet")
            }
        }
    }

    private fun abortProbeForClose() {
        if (probeTransport == null) {
            upgradeController.reset()
            return
        }
        probeFailed = true
        clearProbeSubscriptions()
        probeTransport?.close()
        probeTransport = null
        upgradeController.reset()
    }

    private fun onProbeFailed(name: String, reason: String) {
        workQueue.launch {
            if (probeFailed) return@launch
            probeFailed = true
            log.lifecycleError(TAG, "upgrade probe failed")
            log.devVerbose(TAG) { "probe transport $name failed: $reason" }
            clearProbeSubscriptions()
            probeTransport?.close()
            probeTransport = null
            upgradeController.reset()
            transport?.resume()
            events.emit(EVENT_UPGRADE_ERROR, reason)
            flush()
        }
    }

    private fun maybeCompleteUpgrade() {
        if (!upgradeController.canSwitchTransport()) {
            return
        }
        val probe = probeTransport ?: return
        log.lifecycleInfo(TAG, "upgraded transport=${probe.name}")
        log.devVerbose(TAG) { "changing transport to ${probe.name}" }
        events.emit(EVENT_UPGRADING, probe)
        clearProbeSubscriptions()
        setTransport(probe)
        probeTransport = null
        upgradeController.completeSwitch()
        events.emit(EVENT_UPGRADE, probe)
        flush()
    }

    /**
     * Engine.IO v4 makes the SERVER responsible for heartbeat timing: it sends PING, the client
     * only replies PONG (see [onPacket]'s Ping branch). This starts the watchdog for the first
     * expected PING; every inbound PING re-arms it via [scheduleServerPingWatchdog]. A v3-style
     * client-initiated ping loop was the root cause of a real production drop — the server closed
     * the transport on receiving an unsolicited ping it never asked for, once per pingInterval.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        if (pingInterval <= 0 || pingTimeout <= 0) {
            return
        }
        scheduleServerPingWatchdog()
    }

    /** Re-armed on every inbound PING; fires [SocketError.PingTimeout] if the server goes silent
     *  for longer than its own advertised pingInterval + pingTimeout. */
    private fun scheduleServerPingWatchdog() {
        pingTimeoutJob?.cancel()
        pingTimeoutJob = scope.launch {
            delay((pingInterval + pingTimeout).milliseconds)
            workQueue.launch {
                if (!inactive()) {
                    log.lifecycleError(TAG, SocketError.PingTimeout)
                    log.devVerbose(TAG) { "no server ping within ${pingInterval + pingTimeout}ms" }
                    emitError(SocketError.PingTimeout)
                    onClose("ping timeout")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        pingTimeoutJob?.cancel()
        pingTimeoutJob = null
    }

    private fun onTransportError(cause: Any?) {
        workQueue.launch {
            if (cause is ProtocolParseException) {
                log.lifecycleError(TAG, SocketError.ParseError(cause.raw))
                log.devVerbose(TAG, cause.raw, cause.cause)
                emitError(SocketError.ParseError(cause.raw))
                return@launch
            }
            val error = mapTransportError(cause)
            log.lifecycleError(TAG, error)
            log.devVerbose(TAG, "transport error: $cause", cause as? Throwable)
            emitError(error)
            onClose(error.toString())
        }
    }

    private fun onClose(reason: String, scheduleReconnect: Boolean = true) {
        workQueue.launch {
            finishClose(reason, scheduleReconnect)
        }
    }

    private fun finishClose(reason: String, scheduleReconnect: Boolean) {
        if (state == EngineState.CLOSED) {
            return
        }
        log.lifecycleInfo(TAG, "disconnected")
        log.devVerbose(TAG) { "onClose: $reason" }
        stopHeartbeat()
        clearProbeSubscriptions()
        probeTransport?.close()
        probeTransport = null
        upgradeController.reset()
        clearTransportSubscriptions()
        transport?.close()
        transport = null
        writeBuffer.clear()
        prevBufferLen = 0
        sessionId = ""
        upgrades = emptyList()
        state = EngineState.CLOSED
        events.emit(EVENT_CLOSE, reason)

        val pendingOpen = openPending
        openPending = false
        if (pendingOpen) {
            openOnWorker()
            return
        }

        val shouldReconnect = scheduleReconnect &&
            !intentionalClose &&
            options.reconnection &&
            reconnectPolicy.canRetry()
        intentionalClose = false
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectPaused) {
            return
        }
        reconnectJob?.cancel()
        val delayMs = reconnectPolicy.nextDelayMs()
        if (delayMs <= 0L) {
            return
        }
        log.lifecycleInfo(TAG, "reconnecting attempt=${reconnectPolicy.attempt}")
        log.devVerbose(TAG) { "schedule reconnect in ${delayMs}ms (attempt=${reconnectPolicy.attempt})" }
        reconnectJob = scope.launch {
            delay(delayMs.milliseconds)
            workQueue.launch {
                if (state == EngineState.CLOSED) {
                    open()
                }
            }
        }
    }

    private fun emitError(error: SocketError) {
        workQueue.launch {
            _errors.emit(error)
            events.emit(EVENT_ERROR, error)
        }
    }

    private fun inactive(): Boolean = state == EngineState.CLOSED || state == EngineState.CLOSING

    companion object {
        const val EVENT_OPEN = "open"
        const val EVENT_CLOSE = "close"
        const val EVENT_PACKET = "packet"
        const val EVENT_DRAIN = "drain"
        const val EVENT_FLUSH = "flush"
        const val EVENT_HANDSHAKE = "handshake"
        const val EVENT_DATA = "data"
        const val EVENT_PING = "ping"
        const val EVENT_ERROR = "error"
        const val EVENT_UPGRADE = "upgrade"
        const val EVENT_UPGRADE_ERROR = "upgradeError"
        const val EVENT_UPGRADING = "upgrading"
        const val EVENT_BINARY_DATA = "binaryData"

        private const val PROBE = "probe"
        private const val TAG = "EngineConnection"
    }
}
