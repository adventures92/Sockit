package dev.adven.sockit.api

import kotlinx.serialization.json.JsonElement

public interface StreamCommand {
    public val eventName: String
    public val payload: JsonElement
}
