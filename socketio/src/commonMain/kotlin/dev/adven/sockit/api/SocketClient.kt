package dev.adven.sockit.api

import dev.adven.sockit.socketio.ConnectionManager
import dev.adven.sockit.socketio.SocketClientRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

public class SocketClient internal constructor(
    private val manager: ConnectionManager,
    private val registryKey: String?,
) {
    /**
     * Reflects aggregate connection state across all namespaces on this client.
     */
    public fun isConnected(): Boolean = connectionState.value is ConnectionState.Connected

    /**
     * Reflects aggregate connection state across all namespaces on this client.
     */
    public fun isDisconnected(): Boolean = connectionState.value is ConnectionState.Disconnected

    public val connectionState: StateFlow<ConnectionState> = manager.connectionState

    /**
     * Aggregate error stream across the engine and all namespaces on this client.
     * Prefer [NamespaceSocket.errors] when filtering by namespace.
     */
    public val errors: Flow<SocketError> = manager.errors

    public fun namespace(path: String = "/"): NamespaceSocket = manager.namespace(path)

    public suspend fun openAwait(path: String = "/") {
        namespace(path).openAwait()
    }

    /**
     * Suspends until the event is accepted into the outbound queue for [path].
     * See [NamespaceSocket.emitAwait] for queue vs async [SocketError.SendFailed] semantics.
     */
    public suspend fun emitAwait(path: String = "/", event: String, vararg payloads: Any?) {
        namespace(path).emitAwait(event, *payloads)
    }

    /**
     * Same queue semantics as [emitAwait] for a [StreamCommand] on [path].
     */
    public suspend fun emitAwait(path: String = "/", command: StreamCommand) {
        namespace(path).emitAwait(command)
    }

    /**
     * Non-blocking release from the multiplex registry. The engine may still be tearing down on a
     * background worker after this returns; use [closeAwait] when teardown must finish first.
     */
    public fun close() {
        SocketClientRegistry.release(registryKey, manager)
    }

    /**
     * Stops automatic reconnect scheduling (cancels any pending backoff timer).
     *
     * Does **not** close an active transport — emits and inbound events continue until the
     * connection drops naturally or you call [close] / [NamespaceSocket.close].
     *
     * The library does not decide *when* to call this; the host app invokes it from its own
     * policy (lifecycle, connectivity, business rules).
     */
    public fun pauseReconnect() {
        manager.pauseReconnect()
    }

    /**
     * Re-enables automatic reconnect scheduling and opens the engine immediately when namespaces
     * still want a connection and the engine is closed.
     */
    public fun resumeReconnect() {
        manager.resumeReconnect()
    }

    /**
     * Suspends until registry release and engine destruction complete on the worker queue.
     */
    public suspend fun closeAwait() {
        SocketClientRegistry.releaseAwait(registryKey, manager)
    }

    public companion object {
        public suspend fun connect(
            url: String,
            options: SocketOptions = socketOptions { },
        ): SocketClient = withContext(Dispatchers.Default) {
            val entry = SocketClientRegistry.acquire(url, options)
            SocketClient(entry.manager, entry.cacheKey)
        }
    }
}
