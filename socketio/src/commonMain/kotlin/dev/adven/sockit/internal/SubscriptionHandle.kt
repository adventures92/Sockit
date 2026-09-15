package dev.adven.sockit.internal

internal class SubscriptionHandle(
    private val bus: EventBus,
    private val event: String,
    private val listener: EventBus.Listener,
    private val once: Boolean,
) {
    fun destroy() {
        bus.remove(event, listener, once)
    }
}
