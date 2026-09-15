package dev.adven.sockit.api

import dev.adven.sockit.internal.logVerboseDroppedTransports
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

public fun socketOptions(block: SocketOptionsBuilder.() -> Unit): SocketOptions = SocketOptionsBuilder().apply(block).build()

public class SocketOptionsBuilder {
    public var path: String = "/socket.io/"
    private var transportList: List<String> = listOf(Transports.POLLING, Transports.WEBSOCKET)

    public fun transports(vararg names: String) {
        transportList = names.toList()
    }

    public var upgrade: Boolean = true
    private var authPayload: JsonObject = JsonObject(emptyMap())

    /**
     * Sets the `auth` payload sent in the Socket.IO CONNECT packet, using kotlinx.serialization's
     * [JsonObjectBuilder] DSL so values may be strings, numbers, booleans, or nested objects:
     *
     * ```
     * auth {
     *     put("token", "jwt")
     *     putJsonObject("meta") { put("deviceId", 42) }
     * }
     * ```
     *
     * Multiple calls accumulate; later keys override earlier ones.
     */
    public fun auth(block: JsonObjectBuilder.() -> Unit) {
        authPayload = JsonObject(authPayload + buildJsonObject(block))
    }

    private val headersMap = mutableMapOf<String, List<String>>()

    public fun extraHeaders(block: MutableMap<String, List<String>>.() -> Unit) {
        headersMap.block()
    }

    public var timeoutMs: Long = 20_000

    /**
     * Timeout for a pending [NamespaceSocket.emitWithAck] acknowledgement, in milliseconds.
     * `0` (default) disables the timer; the pending ack is still failed if the connection drops,
     * so it never hangs indefinitely. When `> 0`, the flow fails with [SocketError.Timeout] if no
     * `ACK` arrives in time.
     */
    public var ackTimeoutMs: Long = 0

    public var reconnection: Boolean = true
    public var reconnectionAttempts: Int = Int.MAX_VALUE
    public var reconnectionDelayMs: Long = 1_000
    public var reconnectionDelayMaxMs: Long = 5_000
    public var randomizationFactor: Double = 0.5
    public var multiplex: Boolean = true
    public var forceNew: Boolean = false
    public var trustAllCerts: Boolean = false
    public var httpClient: HttpClient? = null
    public var logger: Logger = Logger.NoOp
    public var eventBufferCapacity: Int = 64
    public var eventBufferOverflow: EventBufferOverflow = EventBufferOverflow.DROP_OLDEST

    /**
     * When `true`, [Transports.WEBTRANSPORT] is accepted in [transports] and the internal factory
     * registers a stub transport (opens fail until platform WebTransport is implemented).
     *
     * Default `false` — `webtransport` entries are dropped during normalization (JS parity).
     */
    public var experimentalWebTransport: Boolean = false

    internal fun build(): SocketOptions {
        val transports = normalizeTransports(transportList, logger, experimentalWebTransport)
        logVerboseDroppedTransports(transportList, transports)
        return SocketOptions(
            path = path,
            transports = transports,
            upgrade = upgrade,
            auth = authPayload,
            extraHeaders = headersMap.mapValues { it.value.toList() },
            timeoutMs = timeoutMs,
            ackTimeoutMs = ackTimeoutMs,
            reconnection = reconnection,
            reconnectionAttempts = reconnectionAttempts,
            reconnectionDelayMs = reconnectionDelayMs,
            reconnectionDelayMaxMs = reconnectionDelayMaxMs,
            randomizationFactor = randomizationFactor,
            multiplex = multiplex,
            forceNew = forceNew,
            trustAllCerts = trustAllCerts,
            httpClient = httpClient,
            logger = logger,
            eventBuffer = EventBufferConfig(
                capacity = eventBufferCapacity,
                overflow = eventBufferOverflow,
            ),
            experimentalWebTransport = experimentalWebTransport,
        )
    }
}

public class SocketOptions internal constructor(
    public val path: String,
    public val transports: List<String>,
    public val upgrade: Boolean,
    public val auth: JsonObject,
    public val extraHeaders: Map<String, List<String>>,
    public val timeoutMs: Long,
    public val ackTimeoutMs: Long,
    public val reconnection: Boolean,
    public val reconnectionAttempts: Int,
    public val reconnectionDelayMs: Long,
    public val reconnectionDelayMaxMs: Long,
    public val randomizationFactor: Double,
    public val multiplex: Boolean,
    public val forceNew: Boolean,
    public val trustAllCerts: Boolean,
    public val httpClient: HttpClient?,
    public val logger: Logger,
    public val eventBuffer: EventBufferConfig,
    public val experimentalWebTransport: Boolean,
)
