package dev.adven.sockit.transport

import dev.adven.sockit.protocol.BinaryAssembler
import dev.adven.sockit.protocol.EngineIoCodec
import dev.adven.sockit.protocol.EnginePacket
import dev.adven.sockit.protocol.OutboundEngineMessage
import dev.adven.sockit.protocol.SocketIoCodec
import dev.adven.sockit.protocol.SocketPacket
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

/**
 * Minimal Engine.IO v4 + Socket.IO v5 echo server for JVM tests.
 *
 * [socket-server.js] remains the canonical echo server when Node is available; this embedded server
 * supports Phase 4 transport gates and Phase 6 integration tests without an external process.
 */
internal object EmbeddedEngineIoServer {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val pendingBySid = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val recordedPostBodies = ConcurrentLinkedQueue<String>()
    private val binaryAssemblersBySid = ConcurrentHashMap<String, BinaryAssembler>()
    private val pendingResponsesBySid = ConcurrentHashMap<String, ConcurrentLinkedQueue<OutboundResponse>>()
    private val forcedPollPayload = java.util.concurrent.atomic.AtomicReference<String?>(null)

    var pingIntervalMs: Int = 25_000
        private set
    var pingTimeoutMs: Int = 20_000
        private set
    var respondToPings: Boolean = true
        private set
    var maxPayload: Int = 0
        private set

    var sendServerPings: Boolean = false
        private set

    private val clientPingsReceived = AtomicInteger(0)

    var port: Int = 0
        private set

    fun configure(
        pingIntervalMs: Int = 25_000,
        pingTimeoutMs: Int = 20_000,
        respondToPings: Boolean = true,
        maxPayload: Int = 0,
        sendServerPings: Boolean = false,
    ) {
        this.pingIntervalMs = pingIntervalMs
        this.pingTimeoutMs = pingTimeoutMs
        this.respondToPings = respondToPings
        this.maxPayload = maxPayload
        this.sendServerPings = sendServerPings
    }

    /** Count of Engine.IO PING packets received from any client since the last [stop] — proves (or
     *  disproves) that the client never sends an unsolicited heartbeat ping of its own. */
    fun clientPingCount(): Int = clientPingsReceived.get()

    fun resetConfig() {
        configure()
    }

    private fun openPayload(sid: String): String {
        val maxPayloadField = if (maxPayload > 0) ""","maxPayload":$maxPayload""" else ""
        return """{"sid":"$sid","upgrades":["websocket"],"pingInterval":$pingIntervalMs,"pingTimeout":$pingTimeoutMs$maxPayloadField}"""
    }

    fun start(port: Int = allocateEphemeralPort()) {
        if (server != null) return

        this.port = port
        recordedPostBodies.clear()
        server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                route("/socket.io/") {
                    get {
                        val sid = call.request.queryParameters["sid"]
                        if (sid == null) {
                            val newSid = "poll-${UUID.randomUUID()}"
                            pendingBySid.putIfAbsent(newSid, ConcurrentLinkedQueue())
                            call.respondText("0${openPayload(newSid)}")
                        } else {
                            forcedPollPayload.getAndSet(null)?.let { forced ->
                                call.respondText(forced)
                                return@get
                            }
                            val packet = pendingBySid
                                .getOrPut(sid) { ConcurrentLinkedQueue() }
                                .poll()
                                .orEmpty()
                            call.respondText(packet)
                        }
                    }

                    post {
                        val sid = call.request.queryParameters["sid"]
                        if (sid == null) {
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }
                        val body = call.receiveText()
                        recordedPostBodies.add(body)
                        val responses = processIncoming(sid, body)
                        val queue = pendingBySid.getOrPut(sid) { ConcurrentLinkedQueue() }
                        responses.forEach { outbound ->
                            queue.add(encodeOutboundForPolling(outbound))
                        }
                        call.respondText(responses.joinToString("\u001e") { encodeOutboundForPolling(it) })
                    }

                    webSocket {
                        val sid = call.request.queryParameters["sid"]
                        if (sid == null) {
                            val newSid = "ws-${UUID.randomUUID()}"
                            send(
                                Frame.Text(
                                    "0${openPayload(newSid).replace(
                                        """"upgrades":["websocket"]"""",
                                        """"upgrades":[]""",
                                    )}",
                                ),
                            )
                            listenWebSocket(newSid)
                        } else {
                            listenWebSocket(sid)
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
        port = 0
        pendingBySid.clear()
        recordedPostBodies.clear()
        binaryAssemblersBySid.clear()
        pendingResponsesBySid.clear()
        forcedPollPayload.set(null)
        clientPingsReceived.set(0)
        resetConfig()
    }

    fun postedBodies(): List<String> = recordedPostBodies.toList()

    fun queuePollResponse(sid: String, raw: String) {
        pendingBySid.getOrPut(sid) { ConcurrentLinkedQueue() }.add(raw)
    }

    fun queueNextPollPayload(raw: String) {
        forcedPollPayload.set(raw)
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.listenWebSocket(sid: String) {
        val pingJob = if (sendServerPings) {
            launch {
                while (true) {
                    delay(pingIntervalMs.milliseconds)
                    send(Frame.Text(EngineIoCodec.encode(EnginePacket.Ping())))
                }
            }
        } else {
            null
        }
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        if (isClientPing(text)) clientPingsReceived.incrementAndGet()
                        val responses = processIncoming(sid, text)
                        responses.forEach { outbound ->
                            send(Frame.Text(outbound.engineWire))
                            outbound.binaryAttachments.forEach { attachment ->
                                send(Frame.Binary(true, attachment))
                            }
                        }
                    }
                    is Frame.Binary -> {
                        val responses = addBinaryAttachment(sid, ByteString(frame.readBytes())) +
                            drainPendingResponses(sid)
                        responses.forEach { outbound ->
                            send(Frame.Text(outbound.engineWire))
                            outbound.binaryAttachments.forEach { attachment ->
                                send(Frame.Binary(true, attachment))
                            }
                        }
                    }
                    is Frame.Close -> break
                    else -> Unit
                }
            }
        } finally {
            pingJob?.cancel()
        }
    }

    /** An Engine.IO PING (packet type `2`) that isn't the upgrade-probe variant (`2probe`). */
    private fun isClientPing(text: String): Boolean = text == "2"

    private data class OutboundResponse(
        val engineWire: String,
        val binaryAttachments: List<ByteArray> = emptyList(),
    )

    private fun processIncoming(sid: String, body: String): List<OutboundResponse> {
        val responses = mutableListOf<OutboundResponse>()
        for (record in splitPollingRecords(body)) {
            if (record.startsWith(POLLING_BINARY_PREFIX)) {
                responses += addBinaryAttachment(sid, decodePollingBinary(record))
                responses += drainPendingResponses(sid)
                continue
            }
            val packet = try {
                EngineIoCodec.decode(record)
            } catch (_: Exception) {
                continue
            }
            responses += handleEnginePacket(sid, packet)
        }
        return responses
    }

    private fun splitPollingRecords(body: String): List<String> = if (body.contains(RECORD_SEPARATOR)) {
        body.split(RECORD_SEPARATOR).filter { it.isNotEmpty() }
    } else {
        listOf(body)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodePollingBinary(record: String): ByteString {
        val bytes = Base64.decode(record.substring(POLLING_BINARY_PREFIX.length))
        return ByteString(bytes)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeOutboundForPolling(outbound: OutboundResponse): String {
        if (outbound.binaryAttachments.isEmpty()) {
            return outbound.engineWire
        }
        val message = OutboundEngineMessage(
            packet = EngineIoCodec.decode(outbound.engineWire),
            binaryAttachments = outbound.binaryAttachments.map { attachment -> ByteString(attachment) },
        )
        return EngineIoCodec.encodePollingBatch(listOf(message))
    }

    private fun beginBinaryAssembly(sid: String, packet: SocketPacket.BinaryEvent) {
        binaryAssemblersBySid[sid] = BinaryAssembler(packet) { _, _, raw, attachments ->
            binaryAssemblersBySid.remove(sid)
            val reassembled = BinaryAssembler.reassemble(raw, attachments)
            val responses = handleEvent(sid, SocketPacket.Event(packet.namespace, reassembled))
            pendingResponsesBySid.getOrPut(sid) { ConcurrentLinkedQueue() }.addAll(responses)
        }
    }

    private fun addBinaryAttachment(sid: String, bytes: ByteString): List<OutboundResponse> {
        val assembler = binaryAssemblersBySid[sid] ?: return emptyList()
        assembler.add(bytes)
        val queue = pendingResponsesBySid.remove(sid) ?: return emptyList()
        return queue.toList()
    }

    private fun drainPendingResponses(sid: String): List<OutboundResponse> {
        val queue = pendingResponsesBySid.remove(sid) ?: return emptyList()
        return queue.toList()
    }

    private fun handleEnginePacket(sid: String, packet: EnginePacket): List<OutboundResponse> = when (packet) {
        is EnginePacket.Open -> emptyList()
        is EnginePacket.Close -> listOf(outbound(EngineIoCodec.encode(EnginePacket.Close)))
        is EnginePacket.Ping -> when (packet.payload) {
            "probe" -> listOf(outbound(EngineIoCodec.encode(EnginePacket.Pong("probe"))))
            else -> if (respondToPings) {
                listOf(outbound(EngineIoCodec.encode(EnginePacket.Pong())))
            } else {
                emptyList()
            }
        }
        is EnginePacket.Pong -> emptyList()
        is EnginePacket.Message -> handleSocketPacket(sid, packet.socket)
        is EnginePacket.Upgrade -> emptyList()
        is EnginePacket.Noop -> emptyList()
    }

    private fun outbound(engineWire: String, binaryAttachments: List<ByteArray> = emptyList()) = OutboundResponse(engineWire, binaryAttachments)

    private fun handleSocketPacket(sid: String, packet: SocketPacket): List<OutboundResponse> = when (packet) {
        is SocketPacket.Connect -> {
            val ack = SocketPacket.Connect(packet.namespace, """{"sid":"$sid"}""")
            listOf(outbound(EngineIoCodec.encode(EnginePacket.Message(ack))))
        }
        is SocketPacket.Disconnect -> emptyList()
        is SocketPacket.ConnectError -> emptyList()
        is SocketPacket.Event -> handleEvent(sid, packet)
        is SocketPacket.Ack -> emptyList()
        is SocketPacket.BinaryEvent -> {
            beginBinaryAssembly(sid, packet)
            emptyList()
        }
        is SocketPacket.BinaryAck -> emptyList()
    }

    private fun handleEvent(sid: String, packet: SocketPacket.Event): List<OutboundResponse> {
        val array = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(packet.data) as JsonArray }
            .getOrNull() ?: return emptyList()
        if (array.isEmpty()) return emptyList()
        val eventName = array[0].jsonPrimitive.content
        val args = array.drop(1)

        if (packet.id != null) {
            val ackData = buildJsonArray { args.forEach { add(it) } }.toString()
            val ack = SocketPacket.Ack(packet.namespace, packet.id, ackData)
            return listOf(outbound(EngineIoCodec.encode(EnginePacket.Message(ack))))
        }

        return when (eventName) {
            "echo" -> {
                if (args.size != 1) return emptyList()
                val wire = SocketIoCodec.encodeEvent(packet.namespace, "echoBack", args[0])
                val socketPkt = SocketIoCodec.decode(wire)
                listOf(outbound(EngineIoCodec.encode(EnginePacket.Message(socketPkt))))
            }
            "echoBinary" -> {
                val attachment = ByteString(ECHO_BINARY_ATTACHMENT)
                val (wire, frames) = BinaryAssembler.encodeBinaryEvent(
                    namespace = packet.namespace,
                    eventName = "echoBinaryBack",
                    attachments = listOf(attachment),
                )
                val socketPkt = SocketIoCodec.decode(wire)
                listOf(
                    outbound(
                        engineWire = EngineIoCodec.encode(EnginePacket.Message(socketPkt)),
                        binaryAttachments = frames.map { frame -> frame.toByteArray() },
                    ),
                )
            }
            else -> emptyList()
        }
    }

    private fun ByteString.toByteArray(): ByteArray = ByteArray(size) { index -> this[index] }

    private val ECHO_BINARY_ATTACHMENT = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    private const val RECORD_SEPARATOR = "\u001e"
    private const val POLLING_BINARY_PREFIX = "b"
}
