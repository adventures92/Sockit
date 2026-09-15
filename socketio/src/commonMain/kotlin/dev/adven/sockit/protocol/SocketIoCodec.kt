package dev.adven.sockit.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

internal object SocketIoCodec {
    private const val DEFAULT_NAMESPACE = "/"

    fun encode(packet: SocketPacket): String = buildString {
        when (packet) {
            is SocketPacket.Connect -> {
                append('0')
                appendNamespace(packet.namespace)
                packet.data?.let { append(it) }
            }

            is SocketPacket.Disconnect -> {
                append('1')
                appendNamespace(packet.namespace)
            }

            is SocketPacket.Event -> {
                append('2')
                appendNamespace(packet.namespace)
                packet.id?.let { append(it) }
                append(packet.data)
            }

            is SocketPacket.Ack -> {
                append('3')
                appendNamespace(packet.namespace)
                packet.id?.let { append(it) }
                append(packet.data)
            }

            is SocketPacket.ConnectError -> {
                append('4')
                appendNamespace(packet.namespace)
                append(packet.data)
            }

            is SocketPacket.BinaryEvent -> {
                append('5')
                append(packet.attachmentCount)
                append('-')
                appendNamespace(packet.namespace)
                append(packet.data)
            }

            is SocketPacket.BinaryAck -> {
                append('6')
                append(packet.attachmentCount)
                append('-')
                appendNamespace(packet.namespace)
                packet.id?.let { append(it) }
                append(packet.data)
            }
        }
    }

    fun decode(raw: String): SocketPacket {
        if (raw.isEmpty()) {
            throw IllegalArgumentException("Empty Socket.IO packet")
        }

        var index = 0
        val type = raw[index++].digitToIntOrNull()
            ?: throw IllegalArgumentException("Invalid Socket.IO packet type: ${raw[0]}")

        var attachmentCount = 0
        if (type == 5 || type == 6) {
            val dashIndex = raw.indexOf('-', index)
            if (dashIndex < 0) {
                throw IllegalArgumentException("Binary Socket.IO packet missing attachment count: $raw")
            }
            attachmentCount = raw.substring(index, dashIndex).toInt()
            index = dashIndex + 1
        }

        val (namespace, afterNamespace) = parseNamespace(raw, index)
        index = afterNamespace

        val (ackId, afterAck) = parseAckId(raw, index)
        index = afterAck

        val data = if (index < raw.length) raw.substring(index) else null

        return when (type) {
            0 -> SocketPacket.Connect(namespace, data)
            1 -> SocketPacket.Disconnect(namespace)
            2 -> SocketPacket.Event(namespace, data ?: "[]", ackId)
            3 -> SocketPacket.Ack(namespace, ackId, data ?: "[]")
            4 -> SocketPacket.ConnectError(namespace, data ?: "{}")
            5 -> SocketPacket.BinaryEvent(namespace, data ?: "[]", attachmentCount)
            6 -> SocketPacket.BinaryAck(namespace, ackId, data ?: "[]", attachmentCount)
            else -> throw IllegalArgumentException("Unknown Socket.IO packet type: $type")
        }
    }

    fun encodeEvent(namespace: String, eventName: String, jsonPayload: JsonElement): String {
        val payload = buildJsonArray {
            add(JsonPrimitive(eventName))
            add(jsonPayload)
        }
        return encode(SocketPacket.Event(namespace, payload.toString()))
    }

    fun consumedLength(raw: String): Int {
        if (raw.isEmpty()) return 0
        var index = 1 // packet type digit
        val type = raw[0].digitToIntOrNull() ?: return 1

        if (type == 5 || type == 6) {
            val dashIndex = raw.indexOf('-', index)
            if (dashIndex < 0) return raw.length
            index = dashIndex + 1
        }

        val (_, afterNamespace) = parseNamespace(raw, index)
        index = afterNamespace

        val (_, afterAck) = parseAckId(raw, index)
        index = afterAck

        if (index >= raw.length) return index

        val jsonLength = consumeJsonValue(raw, index)
        return index + jsonLength
    }

    private fun StringBuilder.appendNamespace(namespace: String) {
        if (namespace != DEFAULT_NAMESPACE) {
            append(namespace)
            append(',')
        }
    }

    private fun parseNamespace(raw: String, start: Int): Pair<String, Int> {
        if (start >= raw.length || raw[start] != '/') {
            return DEFAULT_NAMESPACE to start
        }
        val commaIndex = raw.indexOf(',', start)
        if (commaIndex < 0) {
            return raw.substring(start) to raw.length
        }
        return raw.substring(start, commaIndex) to commaIndex + 1
    }

    private fun parseAckId(raw: String, start: Int): Pair<Int?, Int> {
        if (start >= raw.length) return null to start
        val first = raw[start]
        if (first != '[' && first != '{' && first.isDigit()) {
            var end = start
            while (end < raw.length && raw[end].isDigit()) {
                end++
            }
            return raw.substring(start, end).toInt() to end
        }
        return null to start
    }

    private fun consumeJsonValue(raw: String, start: Int): Int {
        if (start >= raw.length) return 0
        return when (raw[start]) {
            '[' -> consumeBalanced(raw, start, '[', ']')
            '{' -> consumeBalanced(raw, start, '{', '}')
            else -> raw.length - start
        }
    }

    private fun consumeBalanced(raw: String, start: Int, open: Char, close: Char): Int {
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
                open -> depth++
                close -> {
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
