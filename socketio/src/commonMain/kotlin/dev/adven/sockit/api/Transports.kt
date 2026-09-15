package dev.adven.sockit.api

/**
 * Engine.IO wire transport names for [SocketOptionsBuilder.transports].
 *
 * v0.1: polling + websocket. [WEBTRANSPORT] is experimental — enable via
 * [SocketOptionsBuilder.experimentalWebTransport]; factory registers a stub until platform support lands.
 */
public object Transports {
    public const val POLLING: String = "polling"
    public const val WEBSOCKET: String = "websocket"

    /**
     * Engine.IO WebTransport wire name (HTTP/3 QUIC bidirectional streams).
     *
     * Requires [SocketOptionsBuilder.experimentalWebTransport] = `true` to pass normalization.
     * Implementation is a stub on all targets until a mobile WebTransport bridge exists.
     */
    public const val WEBTRANSPORT: String = "webtransport"
}

internal fun supportedTransports(enableExperimentalWebTransport: Boolean): Set<String> = buildSet {
    add(Transports.POLLING)
    add(Transports.WEBSOCKET)
    if (enableExperimentalWebTransport) {
        add(Transports.WEBTRANSPORT)
    }
}

internal fun normalizeTransports(
    raw: List<String>,
    logger: Logger,
    enableExperimentalWebTransport: Boolean = false,
): List<String> {
    val supported = supportedTransports(enableExperimentalWebTransport)
    val normalized = raw.filter { it in supported }
    if (normalized.size < raw.size) {
        val dropped = raw.size - normalized.size
        logger.debug(Logger.TAG, "dropped $dropped unsupported transport(s)")
    }
    require(normalized.isNotEmpty()) {
        "No transports available — supported: ${supported.joinToString()}"
    }
    return normalized
}
