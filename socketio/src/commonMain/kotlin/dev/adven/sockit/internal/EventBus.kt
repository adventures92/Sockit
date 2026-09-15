package dev.adven.sockit.internal

internal class EventBus {
    fun interface Listener {
        fun call(vararg args: Any)
    }

    private val persistent = mutableMapOf<String, MutableList<Listener>>()
    private val once = mutableMapOf<String, MutableList<Listener>>()

    fun on(event: String, listener: Listener): SubscriptionHandle {
        addListener(persistent, event, listener)
        return SubscriptionHandle(this, event, listener, once = false)
    }

    fun once(event: String, listener: Listener): SubscriptionHandle {
        addListener(once, event, listener)
        return SubscriptionHandle(this, event, listener, once = true)
    }

    fun emit(event: String, vararg args: Any) {
        persistent[event]?.toList().orEmpty().forEach { it.call(*args) }
        once[event]?.toList().orEmpty().forEach { it.call(*args) }
        once.remove(event)
    }

    internal fun remove(event: String, listener: Listener, once: Boolean) {
        val map = if (once) this.once else persistent
        map[event]?.remove(listener)
        if (map[event].isNullOrEmpty()) {
            map.remove(event)
        }
    }

    private fun addListener(
        map: MutableMap<String, MutableList<Listener>>,
        event: String,
        listener: Listener,
    ) {
        map.getOrPut(event) { mutableListOf() }.add(listener)
    }
}
