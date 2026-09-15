package dev.adven.sockit.socketio

import dev.adven.sockit.api.SocketOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object SocketClientRegistry {
    private val mutex = Mutex()
    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managers = linkedMapOf<String, ConnectionManager>()

    suspend fun acquire(
        url: String,
        options: SocketOptions,
    ): RegistryEntry = mutex.withLock {
        val multiplex = options.multiplex && !options.forceNew
        val key = if (multiplex) ConnectionManager.originKey(url) else null
        if (key != null) {
            managers[key]?.let { existing ->
                existing.acquireClient()
                return@withLock RegistryEntry(existing, key, reused = true)
            }
        }
        val manager = ConnectionManager(url, options)
        manager.acquireClient()
        if (key != null) {
            managers[key] = manager
        }
        RegistryEntry(manager, key, reused = false)
    }

    fun release(key: String?, manager: ConnectionManager) {
        registryScope.launch {
            releaseOnWorker(key, manager)
        }
    }

    suspend fun releaseAwait(key: String?, manager: ConnectionManager) {
        mutex.withLock {
            releaseOnWorkerLocked(key, manager)
        }
    }

    private suspend fun releaseOnWorker(key: String?, manager: ConnectionManager) {
        mutex.withLock {
            releaseOnWorkerLocked(key, manager)
        }
    }

    private suspend fun releaseOnWorkerLocked(key: String?, manager: ConnectionManager) {
        val shouldDestroy = manager.releaseClient()
        if (!shouldDestroy) return
        if (key != null && managers[key] === manager) {
            managers.remove(key)
        }
        manager.destroyAwait()
    }

    /** JVM integration tests — synchronously evict all cached managers between cases. */
    internal suspend fun resetForTests() {
        mutex.withLock {
            managers.values.toList().forEach { manager ->
                manager.destroyAwait()
            }
            managers.clear()
        }
    }

    internal data class RegistryEntry(
        val manager: ConnectionManager,
        val cacheKey: String?,
        val reused: Boolean,
    )
}
