package dev.adven.sockit.api

import kotlinx.coroutines.channels.BufferOverflow

/**
 * Overflow policy for [NamespaceSocket.events] when the collector cannot keep up.
 */
public enum class EventBufferOverflow {
    /** Drop the oldest buffered event (default). */
    DROP_OLDEST,

    /** Drop the newest event when the buffer is full. */
    DROP_LATEST,

    /** Suspend the producer until buffer space is available. */
    SUSPEND,
}

internal fun EventBufferOverflow.toBufferOverflow(): BufferOverflow = when (this) {
    EventBufferOverflow.DROP_OLDEST -> BufferOverflow.DROP_OLDEST
    EventBufferOverflow.DROP_LATEST -> BufferOverflow.DROP_LATEST
    EventBufferOverflow.SUSPEND -> BufferOverflow.SUSPEND
}
