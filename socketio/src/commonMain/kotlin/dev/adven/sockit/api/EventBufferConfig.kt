package dev.adven.sockit.api

/**
 * Buffer settings for [NamespaceSocket.events] hot flows.
 */
public data class EventBufferConfig(
    public val capacity: Int = 64,
    public val overflow: EventBufferOverflow = EventBufferOverflow.DROP_OLDEST,
)
