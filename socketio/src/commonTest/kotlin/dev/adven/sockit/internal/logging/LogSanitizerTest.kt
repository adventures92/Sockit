package dev.adven.sockit.internal.logging

import dev.adven.sockit.api.SocketError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogSanitizerTest {
    @Test
    fun redactsUrls() {
        val sanitized = LogSanitizer.sanitizeMessage("doOpen wss://stream.example.com/socket.io/?token=abc")
        assertFalse(sanitized.contains("stream.example.com"))
        assertEquals("doOpen <url>", sanitized)
    }

    @Test
    fun redactsJwtTokens() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val sanitized = LogSanitizer.sanitizeMessage("auth=$jwt")
        assertEquals("auth=<token>", sanitized)
    }

    @Test
    fun redactsSid() {
        val sanitized = LogSanitizer.sanitizeMessage("onConnect sid=abc123 namespace=/")
        assertEquals("onConnect sid=<redacted> namespace=/", sanitized)
    }

    @Test
    fun summarizesSocketErrorsWithoutPayload() {
        assertEquals("connect error", LogSanitizer.summarize(SocketError.ConnectError("secret details")))
        assertEquals("parse error", LogSanitizer.summarize(SocketError.ParseError("raw packet")))
        assertEquals("ping timeout", LogSanitizer.summarize(SocketError.PingTimeout))
    }

    @Test
    fun droppedTransportsCountOmitsNames() {
        assertEquals("dropped 2 unsupported transport(s)", LogSanitizer.droppedTransportsCount(2))
    }
}
