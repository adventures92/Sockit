package dev.adven.sockit.protocol

internal class ProtocolParseException(
    val raw: String,
    cause: Throwable? = null,
) : Exception(raw, cause)
