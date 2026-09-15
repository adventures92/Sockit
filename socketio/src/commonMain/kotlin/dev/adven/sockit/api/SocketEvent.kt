package dev.adven.sockit.api

public data class SocketEvent(
    val name: String,
    val args: List<SocketPayload>,
) {
    /**
     * Responder for a server-requested acknowledgement, or `null` when the server
     * did not request one for this event.
     *
     * When non-`null`, the server is awaiting a reply: call [Ack.send] exactly once
     * with the acknowledgement payload(s). The reply is sent on the same namespace
     * with the same ack id.
     *
     * Not part of the value identity — it is excluded from [equals]/[hashCode] and
     * is not carried across [copy].
     */
    public var ack: Ack? = null
        internal set
}
