@file:Suppress("unused")

/*
 * Compiles the public API exactly as the README documents it, against the resolved artifact.
 *
 * This file is never run. Its whole job is to fail compilation if a type that appears in
 * dev.adven.sockit.api is not reachable from a consumer's compile classpath — which is what
 * happens when a dependency carrying a public type is declared implementation() instead of api().
 */

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketError
import dev.adven.sockit.api.SocketException
import dev.adven.sockit.api.SocketPayload
import dev.adven.sockit.api.Subscribe
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

suspend fun quickstart(jwt: String) {
    // kotlinx-serialization-json must be on the compile classpath for the auth DSL.
    val client = SocketClient.connect(
        "https://example.com",
        socketOptions {
            transports(Transports.WEBSOCKET, Transports.POLLING)
            auth { put("token", jwt) }
            extraHeaders { put("X-Client", listOf("consumer-smoke")) }
            reconnection = true
            ackTimeoutMs = 5_000
        },
    )
    val socket = client.namespace()

    try {
        socket.openAwait()
        socket.emitAwait("hello", "world")
        socket.emit(Subscribe(buildJsonObject { put("pair", "BTC-INR") }))
    } catch (e: SocketException) {
        when (e.error) {
            is SocketError.ConnectError -> Unit
            is SocketError.Timeout -> Unit
            else -> Unit
        }
    }

    // SocketPayload.Json.element -> JsonElement, SocketPayload.Binary.bytes -> ByteString.
    // Both types come from dependencies :socketio must expose with api().
    when (val payload = socket.events("quote").first().args.firstOrNull()) {
        is SocketPayload.Text -> println(payload.value)
        is SocketPayload.Json -> println((payload.element as? JsonObject)?.get("pair")?.jsonPrimitive?.content)
        is SocketPayload.Binary -> println(payload.bytes.size)
        null -> Unit
    }

    socket.events("server-ping").first().ack?.send("pong")

    if (socket.connectionState.value is ConnectionState.Connected) {
        socket.close()
    }
    client.closeAwait()
}
