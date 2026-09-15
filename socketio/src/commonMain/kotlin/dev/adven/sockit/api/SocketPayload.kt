package dev.adven.sockit.api

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

public sealed interface SocketPayload {
    public data class Text(val value: String) : SocketPayload
    public data class Json(val element: JsonElement) : SocketPayload
    public data class Binary(val bytes: ByteString) : SocketPayload
}

internal fun encodePayload(value: Any?): SocketPayload = when (value) {
    is String -> SocketPayload.Text(value)
    is JsonElement -> SocketPayload.Json(value)
    is ByteString -> SocketPayload.Binary(value)
    is Boolean -> SocketPayload.Json(JsonPrimitive(value))
    is Number -> SocketPayload.Json(numberToJsonPrimitive(value))
    null -> SocketPayload.Json(JsonNull)
    else -> throw IllegalArgumentException("Unsupported payload type: ${value::class}")
}

private fun numberToJsonPrimitive(value: Number): JsonPrimitive = when (value) {
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toDouble())
}
