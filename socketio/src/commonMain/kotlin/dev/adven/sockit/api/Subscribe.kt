package dev.adven.sockit.api

import dev.adven.sockit.internal.StreamEventNames
import kotlinx.serialization.json.JsonElement

public class Subscribe(
    override val payload: JsonElement,
) : StreamCommand {
    override val eventName: String get() = StreamEventNames.SUBSCRIBE
}
