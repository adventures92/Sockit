package dev.adven.sockit.api

/**
 * Sends an acknowledgement back to the server for an event that requested one.
 *
 * Obtained from [SocketEvent.ack], which is non-`null` only when the server
 * emitted the event with an acknowledgement callback and is therefore awaiting a
 * reply (Socket.IO v5: *"the receiver MUST respond with an `ACK` packet with the
 * same event ID"*).
 *
 * Call [send] at most once — subsequent calls for the same event are ignored. The
 * payloads are serialized exactly like [NamespaceSocket.emit] arguments, so JSON
 * values and binary attachments are both supported. If the namespace has
 * disconnected by the time [send] runs, the acknowledgement is dropped (the server
 * is no longer waiting for it) and a [SocketError.SendFailed] is reported on
 * [NamespaceSocket.errors].
 */
public fun interface Ack {
    public fun send(vararg payloads: Any?)
}
