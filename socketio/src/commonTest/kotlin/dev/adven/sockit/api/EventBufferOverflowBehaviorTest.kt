package dev.adven.sockit.api

import kotlinx.coroutines.channels.BufferOverflow
import kotlin.test.Test
import kotlin.test.assertEquals

class EventBufferOverflowBehaviorTest {
    @Test
    fun mapsToKotlinBufferOverflow() {
        assertEquals(BufferOverflow.DROP_OLDEST, EventBufferOverflow.DROP_OLDEST.toBufferOverflow())
        assertEquals(BufferOverflow.DROP_LATEST, EventBufferOverflow.DROP_LATEST.toBufferOverflow())
        assertEquals(BufferOverflow.SUSPEND, EventBufferOverflow.SUSPEND.toBufferOverflow())
    }
}
