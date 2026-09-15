package dev.adven.sockit.protocol

import kotlinx.serialization.Serializable

@Serializable
internal data class OpenHandshake(
    val sid: String,
    val upgrades: List<String> = emptyList(),
    val pingInterval: Int,
    val pingTimeout: Int,
    // Max bytes per HTTP long-polling payload; absent on older servers. See Engine.IO v4.
    val maxPayload: Int? = null,
)
