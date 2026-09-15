package dev.adven.sockit.protocol

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

internal class BinaryAssembler(
    private val packet: SocketPacket,
    // Delivers the packet's data string with placeholders still intact plus the ordered raw
    // attachments, so the caller can decide how to surface binary (e.g. as real bytes).
    private val onComplete: (isAck: Boolean, ackId: Int?, data: String, attachments: List<ByteString>) -> Unit,
) {
    private val expectedAttachments = when (packet) {
        is SocketPacket.BinaryEvent -> packet.attachmentCount
        is SocketPacket.BinaryAck -> packet.attachmentCount
        else -> error("BinaryAssembler requires a binary Socket.IO packet")
    }

    private val buffers = ArrayList<ByteString>(expectedAttachments)

    fun add(buffer: ByteString) {
        buffers.add(buffer)
        if (buffers.size == expectedAttachments) {
            when (packet) {
                is SocketPacket.BinaryEvent -> onComplete(false, null, packetData(), buffers.toList())
                is SocketPacket.BinaryAck -> onComplete(true, packet.id, packetData(), buffers.toList())
                else -> Unit
            }
        }
    }

    fun isComplete(): Boolean = buffers.size == expectedAttachments

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encodeBinaryEvent(
            namespace: String,
            eventName: String,
            attachments: List<ByteString>,
            jsonArgs: List<JsonElement> = emptyList(),
        ): Pair<String, List<ByteString>> {
            val placeholders = attachments.mapIndexed { index, _ ->
                buildJsonObject {
                    put("_placeholder", JsonPrimitive(true))
                    put("num", JsonPrimitive(index))
                }
            }

            val payload = buildJsonArray {
                add(JsonPrimitive(eventName))
                jsonArgs.forEach { add(it) }
                placeholders.forEach { add(it) }
            }

            val wire = SocketIoCodec.encode(
                SocketPacket.BinaryEvent(
                    namespace = namespace,
                    data = payload.toString(),
                    attachmentCount = attachments.size,
                ),
            )
            return wire to attachments
        }

        fun encodeBinaryEvent(
            namespace: String,
            eventName: String,
            attachments: List<ByteString>,
            vararg jsonArgs: JsonElement,
        ): Pair<String, List<ByteString>> = encodeBinaryEvent(
            namespace = namespace,
            eventName = eventName,
            attachments = attachments,
            jsonArgs = jsonArgs.toList(),
        )

        fun encodeBinaryAck(
            namespace: String,
            ackId: Int?,
            attachments: List<ByteString>,
            jsonArgs: List<JsonElement> = emptyList(),
        ): Pair<String, List<ByteString>> {
            val placeholders = attachments.mapIndexed { index, _ ->
                buildJsonObject {
                    put("_placeholder", JsonPrimitive(true))
                    put("num", JsonPrimitive(index))
                }
            }

            val payload = buildJsonArray {
                jsonArgs.forEach { add(it) }
                placeholders.forEach { add(it) }
            }

            val wire = SocketIoCodec.encode(
                SocketPacket.BinaryAck(
                    namespace = namespace,
                    id = ackId,
                    data = payload.toString(),
                    attachmentCount = attachments.size,
                ),
            )
            return wire to attachments
        }

        fun reassemble(dataWithPlaceholders: String, attachments: List<ByteString>): String = replacePlaceholders(dataWithPlaceholders, attachments)

        /** Replaces binary placeholders nested inside a single JSON element (hex-encoded), leaving other nodes intact. */
        fun reassembleElement(element: JsonElement, attachments: List<ByteString>): JsonElement = replaceInElement(element, attachments)

        private fun replacePlaceholders(data: String, attachments: List<ByteString>): String {
            val root = json.parseToJsonElement(data)
            val replaced = replaceInElement(root, attachments)
            return replaced.toString()
        }

        private fun replaceInElement(element: JsonElement, attachments: List<ByteString>): JsonElement = when (element) {
            is JsonObject -> {
                if (isPlaceholder(element)) {
                    val index = element["num"]?.jsonPrimitive?.int
                        ?: throw IllegalArgumentException("Placeholder missing num: $element")
                    attachments.getOrNull(index)?.let { bytes ->
                        JsonPrimitive(bytes.toHexString())
                    } ?: element
                } else {
                    buildJsonObject {
                        element.forEach { (key, value) ->
                            put(key, replaceInElement(value, attachments))
                        }
                    }
                }
            }

            is JsonArray -> buildJsonArray {
                element.forEach { add(replaceInElement(it, attachments)) }
            }

            else -> element
        }

        private fun isPlaceholder(element: JsonObject): Boolean = element["_placeholder"]?.jsonPrimitive?.content == "true" &&
            element.containsKey("num")

        private fun buildJsonObject(block: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
            val map = linkedMapOf<String, JsonElement>()
            map.block()
            return JsonObject(map)
        }

        private fun ByteString.toHexString(): String {
            val bytes = this
            return buildString(bytes.size * 2) {
                for (index in 0 until bytes.size) {
                    val byte = bytes[index].toInt() and 0xFF
                    append(byte.toString(16).padStart(2, '0'))
                }
            }
        }
    }

    private fun packetData(): String = when (packet) {
        is SocketPacket.BinaryEvent -> packet.data
        is SocketPacket.BinaryAck -> packet.data
        else -> error("BinaryAssembler requires a binary Socket.IO packet")
    }
}
