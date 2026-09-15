package dev.adven.sockit.api

/**
 * Thrown by the suspending members of [NamespaceSocket] and [SocketClient] when an operation
 * cannot complete.
 *
 * Every instance carries the same sealed [SocketError] used by the [NamespaceSocket.errors] and
 * [SocketClient.errors] streams, so one `when` handles a failure regardless of whether it arrived
 * by throw or by flow:
 *
 * ```kotlin
 * try {
 *     socket.openAwait()
 *     socket.emitAwait("hello", "world")
 * } catch (e: SocketException) {
 *     when (e.error) {
 *         is SocketError.ConnectError -> refreshCredentials()
 *         is SocketError.Timeout -> retryLater()
 *         else -> report(e.error)
 *     }
 * }
 * ```
 *
 * The two subclasses distinguish *where* the failure occurred; catching [SocketException] itself is
 * usually enough. The hierarchy is sealed — new failure modes surface as new [SocketError] values,
 * never as new exception types.
 */
public sealed class SocketException(
    /** The failure, in the same vocabulary as the `errors` streams. */
    public val error: SocketError,
) : Exception(error.toString()) {
    /**
     * The namespace could not reach [ConnectionState.Connected] — thrown by
     * [NamespaceSocket.openAwait] and [SocketClient.openAwait].
     */
    public class ConnectionFailed internal constructor(error: SocketError) : SocketException(error)

    /**
     * The event was rejected before it reached the outbound queue — thrown by
     * [NamespaceSocket.emitAwait], [SocketClient.emitAwait] and [NamespaceSocket.emitWithAck].
     *
     * A write that fails *after* the event was queued is reported on [NamespaceSocket.errors] as
     * [SocketError.SendFailed] instead; see [NamespaceSocket.emitAwait] for the full contract.
     */
    public class SendFailed internal constructor(error: SocketError) : SocketException(error)
}
