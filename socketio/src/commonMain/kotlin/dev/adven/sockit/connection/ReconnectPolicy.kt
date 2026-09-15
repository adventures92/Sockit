package dev.adven.sockit.connection

import kotlin.math.min
import kotlin.random.Random

internal class ReconnectPolicy(
    private val minMs: Long = 1_000,
    private val maxMs: Long = 5_000,
    private val jitter: Double = 0.5,
    private val maxAttempts: Int = Int.MAX_VALUE,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun nextDelayMs(): Long {
        if (!canRetry()) return 0L
        val exponent = min(attempt, 30)
        val base = min(minMs * (1L shl exponent), maxMs)
        val jitterRange = (base * jitter).toLong()
        val jitterOffset = if (jitterRange == 0L) {
            0L
        } else {
            random.nextLong(-jitterRange, jitterRange + 1)
        }
        val delay = (base + jitterOffset).coerceAtLeast(0L)
        attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }

    fun canRetry(): Boolean = attempt < maxAttempts
}
