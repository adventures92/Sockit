package dev.adven.sockit.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class EventBusTest {
    @Test
    fun onceFiresOnlyOnce() {
        val bus = EventBus()
        var count = 0
        bus.once("test", EventBus.Listener { count++ })
        bus.emit("test")
        bus.emit("test")
        assertEquals(1, count)
    }

    @Test
    fun subscriptionHandleDestroyRemovesListener() {
        val bus = EventBus()
        var count = 0
        val handle = bus.on("test", EventBus.Listener { count++ })
        bus.emit("test")
        assertEquals(1, count)
        handle.destroy()
        bus.emit("test")
        assertEquals(1, count)
    }
}
