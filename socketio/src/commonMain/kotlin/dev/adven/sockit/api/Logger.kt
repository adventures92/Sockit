package dev.adven.sockit.api

public interface Logger {
    public fun debug(tag: String, message: String)
    public fun info(tag: String, message: String)
    public fun error(tag: String, message: String, throwable: Throwable? = null)

    public companion object NoOp : Logger {
        /**
         * Single log tag for all consumer-facing library output.
         * Filter on this in the host app — internal component names are not exposed.
         */
        public const val TAG: String = "SocketIO"

        override fun debug(tag: String, message: String): Unit = Unit
        override fun info(tag: String, message: String): Unit = Unit
        override fun error(tag: String, message: String, throwable: Throwable?): Unit = Unit

        /**
         * Forwards sanitized library lifecycle messages to the host sink.
         * Wire payloads, URLs, and tokens are redacted before [log] is invoked.
         * [log] always receives [TAG] as the tag argument.
         */
        public fun essential(
            log: (tag: String, level: String, message: String, throwable: Throwable?) -> Unit,
        ): Logger = EssentialLogger(log)
    }
}

private class EssentialLogger(
    private val log: (tag: String, level: String, message: String, throwable: Throwable?) -> Unit,
) : Logger {
    override fun debug(tag: String, message: String) {
        log(tag, "debug", message, null)
    }

    override fun info(tag: String, message: String) {
        log(tag, "info", message, null)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        log(tag, "error", message, throwable)
    }
}
