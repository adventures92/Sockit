package dev.adven.sockit.socketio

import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Every other Socket.IO client treats a non-root path on the connect URL as the default namespace
 * (e.g. `io("https://host/auth-stream")` joins `/auth-stream`, not `/`). Sockit never implemented
 * that convention, so a URL built the same way silently landed on the root namespace instead — a
 * real production divergence (see docs/superpowers/specs/2026-08-18-sockit-reconnect-drop-design.md
 * at the workspace root, Candidate 2). These tests pin the corrected resolution down without a live
 * connection, since [ConnectionManager.namespace] resolution is pure string logic.
 */
class ConnectionManagerNamespaceTest {

    private fun options() = socketOptions {
        transports(Transports.WEBSOCKET)
        reconnection = false
    }

    @Test
    fun defaultNamespaceRequestResolvesToTheUrlsPathWhenPresent() {
        val manager = ConnectionManager("wss://host/auth-stream", options())
        val viaDefault = manager.namespace("/")
        val viaExplicitUrlPath = manager.namespace("/auth-stream")
        assertSame(viaExplicitUrlPath, viaDefault)
    }

    @Test
    fun defaultNamespaceRequestStaysRootWhenUrlHasNoPath() {
        val manager = ConnectionManager("wss://host", options())
        val default = manager.namespace("/")
        val other = manager.namespace("/other")
        assertNotSame(other, default)
    }

    @Test
    fun explicitNamespaceOverridesTheUrlDerivedDefault() {
        val manager = ConnectionManager("wss://host/auth-stream", options())
        val explicit = manager.namespace("/something-else")
        val default = manager.namespace("/")
        assertNotSame(explicit, default)
    }
}
