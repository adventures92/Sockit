package dev.adven.sockit.api

import kotlinx.serialization.json.JsonElement

public data class SocketCommand(
    override val eventName: String,
    override val payload: JsonElement,
) : StreamCommand
