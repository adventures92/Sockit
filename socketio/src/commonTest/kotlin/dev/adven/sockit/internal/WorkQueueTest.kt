package dev.adven.sockit.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkQueueTest {
    @Test
    fun serializesJobs() = runTest {
        val queue = WorkQueue()
        var value = 0
        val done = CompletableDeferred<Unit>()
        queue.launch { value += 1 }
        queue.launch {
            value += 2
            done.complete(Unit)
        }
        done.await()
        assertEquals(3, value)
    }
}
