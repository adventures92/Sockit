package dev.adven.sockit.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface NamespaceSocket {
    public val connectionState: StateFlow<ConnectionState>
    public val id: StateFlow<String?>
    public val errors: Flow<SocketError>

    public fun isConnected(): Boolean
    public fun isDisconnected(): Boolean

    public fun open()
    public fun close()

    public fun emit(event: String, vararg payloads: Any?)
    public fun emit(command: StreamCommand)

    /**
     * Suspends until the event is accepted into the outbound queue (WorkQueue → engine write buffer).
     * Completes when enqueue succeeds; does **not** await transport drain.
     *
     * Throws only on **pre-queue** rejection (e.g. reserved event names, payload encoding errors).
     * Async transport [SocketError.SendFailed] after enqueue is reported on [errors] only — it does
     * not fail a prior [emitAwait] that already returned.
     */
    public suspend fun emitAwait(event: String, vararg payloads: Any?)

    /**
     * Same queue semantics as [emitAwait] for a [StreamCommand].
     */
    public suspend fun emitAwait(command: StreamCommand)

    public fun emitWithAck(event: String, vararg payloads: Any?): Flow<SocketEvent>

    /**
     * Cold [Flow] of events named [name]. Backed by a hot buffer configured via
     * [SocketOptionsBuilder.eventBufferCapacity] and [SocketOptionsBuilder.eventBufferOverflow]
     * (default: capacity 64, [EventBufferOverflow.DROP_OLDEST]).
     *
     * When the collector is slower than the producer, overflow policy applies — oldest events
     * are dropped by default rather than blocking the socket worker.
     */
    public fun events(name: String): Flow<SocketEvent>

    public suspend fun openAwait()
}
