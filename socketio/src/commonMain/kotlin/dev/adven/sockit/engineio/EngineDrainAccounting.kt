package dev.adven.sockit.engineio

import dev.adven.sockit.internal.logging.SocketLog
import dev.adven.sockit.protocol.OutboundEngineMessage

internal fun drainWriteBuffer(
    buffer: ArrayDeque<OutboundEngineMessage>,
    count: Int,
    log: SocketLog,
): Int {
    if (count > buffer.size) {
        log.devVerbose(
            TAG,
            "onDrain defensive skip: count=$count > writeBuffer.size=${buffer.size}",
        )
        return 0
    }
    repeat(count) {
        buffer.removeFirstOrNull()
    }
    return count
}

private const val TAG = "EngineConnection"
