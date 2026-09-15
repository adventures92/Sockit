package dev.adven.sockit.connection

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectPolicyTest {
    @Test
    fun delayIncreasesWithAttempt() {
        val policy = ReconnectPolicy(minMs = 1_000, maxMs = 5_000, jitter = 0.0)
        assertEquals(1_000, policy.nextDelayMs())
        assertEquals(2_000, policy.nextDelayMs())
        assertEquals(4_000, policy.nextDelayMs())
    }

    @Test
    fun delayCapsAtMaxMs() {
        val policy = ReconnectPolicy(minMs = 1_000, maxMs = 3_000, jitter = 0.0)
        policy.nextDelayMs()
        policy.nextDelayMs()
        assertEquals(3_000, policy.nextDelayMs())
    }

    @Test
    fun resetClearsAttempt() {
        val policy = ReconnectPolicy(minMs = 1_000, maxMs = 5_000, jitter = 0.0)
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.reset()
        assertEquals(0, policy.attempt)
        assertEquals(1_000, policy.nextDelayMs())
    }

    @Test
    fun canRetryRespectsMaxAttempts() {
        val policy = ReconnectPolicy(minMs = 100, maxMs = 1_000, jitter = 0.0, maxAttempts = 2)
        assertTrue(policy.canRetry())
        policy.nextDelayMs()
        assertTrue(policy.canRetry())
        policy.nextDelayMs()
        assertFalse(policy.canRetry())
        assertEquals(0, policy.nextDelayMs())
    }

    @Test
    fun jitterAppliedWithinRange() {
        val policy = ReconnectPolicy(
            minMs = 1_000,
            maxMs = 5_000,
            jitter = 0.5,
            random = Random(0),
        )
        val delay = policy.nextDelayMs()
        assertTrue(delay in 500..1_500)
    }
}
