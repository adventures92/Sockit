package dev.adven.sockit.protocol

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal object EngineIoCodec {
    private const val RECORD_SEPARATOR = "\u001e"
    private const val POLLING_BINARY_PREFIX = "b"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(packet: EnginePacket): String = when (packet) {
        is EnginePacket.Open -> {
            val handshake = json.encodeToString(
                OpenHandshake.serializer(),
                OpenHandshake(
                    sid = packet.sid,
                    upgrades = packet.upgrades,
                    pingInterval = packet.pingInterval,
                    pingTimeout = packet.pingTimeout,
                    maxPayload = packet.maxPayload.takeIf { it > 0 },
                ),
            )
            "0$handshake"
        }

        EnginePacket.Close -> "1"
        is EnginePacket.Ping -> if (packet.payload != null) "2${packet.payload}" else "2"
        is EnginePacket.Pong -> if (packet.payload != null) "3${packet.payload}" else "3"
        is EnginePacket.Message -> "4${SocketIoCodec.encode(packet.socket)}"
        EnginePacket.Upgrade -> "5"
        EnginePacket.Noop -> "6"
    }

    fun decode(raw: String): EnginePacket {
        if (raw.isEmpty()) {
            throw IllegalArgumentException("Empty Engine.IO packet")
        }

        val type = raw[0]
        val payload = raw.substring(1)

        return when (type) {
            '0' -> decodeOpen(payload)
            '1' -> EnginePacket.Close
            '2' -> EnginePacket.Ping(payload.ifEmpty { null })
            '3' -> EnginePacket.Pong(payload.ifEmpty { null })
            '4' -> EnginePacket.Message(SocketIoCodec.decode(payload))
            '5' -> EnginePacket.Upgrade
            '6' -> EnginePacket.Noop
            else -> throw IllegalArgumentException("Unknown Engine.IO packet type: $type")
        }
    }

    fun decodeBatch(raw: String): List<EnginePacket> {
        if (raw.isEmpty()) return emptyList()

        if (raw.contains(RECORD_SEPARATOR)) {
            return raw.split(RECORD_SEPARATOR)
                .filter { it.isNotEmpty() }
                .map { decode(it) }
        }

        val packets = mutableListOf<EnginePacket>()
        var remaining = raw
        while (remaining.isNotEmpty()) {
            val length = consumedLength(remaining)
            if (length <= 0) break
            packets += decode(remaining.substring(0, length))
            remaining = remaining.substring(length)
        }
        return packets
    }

    fun encodeBatch(packets: List<EnginePacket>): String = packets.joinToString(RECORD_SEPARATOR) { encode(it) }

    fun encodePollingBatch(messages: List<OutboundEngineMessage>): String = messages
        .flatMap { encodeMessageParts(it) }
        .joinToString(RECORD_SEPARATOR)

    /**
     * Number of leading [messages] that fit into a single HTTP long-polling payload without
     * exceeding [maxPayload] bytes (UTF-8, matching how the server measures it). Always returns
     * at least 1 so a single oversized message still makes progress. A non-positive [maxPayload]
     * means "no limit" and returns [messages].size.
     */
    fun pollingBatchFit(messages: List<OutboundEngineMessage>, maxPayload: Int): Int {
        if (maxPayload <= 0 || messages.size <= 1) return messages.size
        var totalBytes = 0
        var emittedParts = 0
        var fitCount = 0
        for (message in messages) {
            val parts = encodeMessageParts(message)
            val partBytes = parts.sumOf { it.encodeToByteArray().size }
            // Joining P parts uses P-1 separators; adding these parts to a non-empty batch
            // introduces one extra separator per part, otherwise parts.size - 1.
            val separatorDelta = if (emittedParts > 0) parts.size else parts.size - 1
            val delta = partBytes + separatorDelta
            if (fitCount > 0 && totalBytes + delta > maxPayload) break
            totalBytes += delta
            emittedParts += parts.size
            fitCount++
        }
        return fitCount
    }

    /**
     * True if [message] alone (its packet plus any binary attachment frames) would exceed
     * [maxPayload] bytes on the wire. A non-positive [maxPayload] means "no limit".
     */
    fun exceedsMaxPayload(message: OutboundEngineMessage, maxPayload: Int): Boolean {
        if (maxPayload <= 0) return false
        val parts = encodeMessageParts(message)
        val partBytes = parts.sumOf { it.encodeToByteArray().size }
        val separatorBytes = (parts.size - 1).coerceAtLeast(0)
        return partBytes + separatorBytes > maxPayload
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeMessageParts(message: OutboundEngineMessage): List<String> {
        val parts = mutableListOf(encode(message.packet))
        message.binaryAttachments.forEach { attachment ->
            parts += POLLING_BINARY_PREFIX + Base64.encode(attachment.toByteArray())
        }
        return parts
    }

    private fun kotlinx.io.bytestring.ByteString.toByteArray(): ByteArray = ByteArray(size) { index -> this[index] }

    private fun consumedLength(raw: String): Int {
        if (raw.isEmpty()) return 0
        return when (raw[0]) {
            '0' -> 1 + consumeJsonObject(raw, 1)
            '1', '5', '6' -> 1
            '2' -> if (raw.length > 1 && raw.startsWith("2probe", 0)) raw.length else 1
            '3' -> if (raw.length > 1 && raw[1] != '0' && raw[1] != '{' && raw[1] != '[') {
                // Pong may carry probe payload as plain text after type char.
                raw.length
            } else {
                1
            }

            '4' -> 1 + SocketIoCodec.consumedLength(raw.substring(1))
            else -> throw IllegalArgumentException("Unknown Engine.IO packet type: ${raw[0]}")
        }
    }

    private fun decodeOpen(payload: String): EnginePacket.Open {
        val handshake = json.decodeFromString(OpenHandshake.serializer(), payload)
        return EnginePacket.Open(
            sid = handshake.sid,
            pingInterval = handshake.pingInterval,
            pingTimeout = handshake.pingTimeout,
            upgrades = handshake.upgrades,
            maxPayload = handshake.maxPayload ?: 0,
        )
    }

    private fun consumeJsonObject(raw: String, start: Int): Int {
        if (start >= raw.length || raw[start] != '{') return 0
        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until raw.length) {
            val char = raw[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return index - start + 1
                    }
                }
            }
        }

        return raw.length - start
    }
}
