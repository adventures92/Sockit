package dev.adven.sockit.transport

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Starts the JVM transport test server.
 *
 * Prefers the Node [socket-server.js] echo server when `node` and `node_modules` are available
 * (Phase 6 parity). Falls back to [EmbeddedEngineIoServer] for Phase 4 gates otherwise.
 *
 * Binds an ephemeral port per JVM (via [allocateEphemeralPort]) so parallel test workers and
 * leftover processes on :3000 do not cause [java.net.BindException].
 */
internal object SocketTestServer {
    private val lock = Any()
    private var refCount = 0
    private var nodeProcess: Process? = null
    private var serverOutputThread: Thread? = null
    private var usingEmbedded = false
    private var activePort: Int = 0

    val port: Int
        get() = activePort

    val isEmbedded: Boolean
        get() = usingEmbedded

    fun postedBodies(): List<String> = if (usingEmbedded) EmbeddedEngineIoServer.postedBodies() else emptyList()

    private const val NODE_START_TIMEOUT_MS = 15_000L
    private const val READY_POLL_INTERVAL_MS = 50L

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                synchronized(lock) {
                    destroyNodeProcess()
                }
            },
        )
    }

    fun start() {
        synchronized(lock) {
            if (refCount++ == 0) {
                activePort = allocateEphemeralPort()
                startServerLocked(forceEmbedded = false)
                awaitReadyLocked()
            }
        }
    }

    /**
     * Starts the embedded Engine.IO server with custom heartbeat settings (test-only).
     * Skips Node even when `node_modules` is present.
     */
    fun startEmbedded(
        pingIntervalMs: Int = 25_000,
        pingTimeoutMs: Int = 20_000,
        respondToPings: Boolean = true,
        sendServerPings: Boolean = false,
    ) {
        synchronized(lock) {
            if (refCount > 0) {
                stopServerLocked()
                refCount = 0
            }
            refCount++
            activePort = allocateEphemeralPort()
            EmbeddedEngineIoServer.configure(
                pingIntervalMs = pingIntervalMs,
                pingTimeoutMs = pingTimeoutMs,
                respondToPings = respondToPings,
                sendServerPings = sendServerPings,
            )
            startServerLocked(forceEmbedded = true)
            awaitReadyLocked()
        }
    }

    /**
     * Stops the running server process but keeps [port] and ref-count so [restartNode] can
     * re-bind the same port (reconnect integration tests).
     */
    fun dropActiveServer() {
        synchronized(lock) {
            stopServerLocked()
        }
    }

    /**
     * Stops the current server, waits for the port to be released, then starts Node again.
     * Use in integration tests that simulate server drop + recovery (Node only).
     */
    fun restartNode() {
        synchronized(lock) {
            val restartPort = activePort
            check(restartPort != 0) { "restartNode: no active port — was the shared server stopped?" }
            stopServerLocked()
            waitForPortReleased(restartPort)
            activePort = restartPort
            if (refCount == 0) {
                refCount = 1
            }
            startServerLocked(forceEmbedded = false)
            awaitReadyLocked()
        }
    }

    /**
     * Embedded Engine.IO for transport/engine ping-pong wire tests.
     *
     * Node EIO v4 rejects client-initiated pings ("invalid heartbeat direction"); only the
     * embedded test server answers client pings with pongs. Long [pingIntervalMs] avoids
     * server-initiated pings colliding with the assertion window.
     */
    fun startForPingPongTest() {
        startEmbedded(
            pingIntervalMs = 60_000,
            pingTimeoutMs = 30_000,
            respondToPings = true,
        )
    }

    fun awaitReady(timeoutMs: Long = NODE_START_TIMEOUT_MS) {
        synchronized(lock) {
            awaitReadyLocked(timeoutMs)
        }
    }

    internal fun isHealthy(): Boolean = synchronized(lock) {
        activePort != 0 && isAcceptingConnections()
    }

    fun stop() {
        synchronized(lock) {
            if (refCount == 0) return
            refCount--
            if (refCount == 0) {
                stopServerLocked()
                activePort = 0
            }
        }
    }

    private fun startServerLocked(forceEmbedded: Boolean) {
        if (!forceEmbedded && tryStartNodeServer(activePort) && isAcceptingConnections()) {
            usingEmbedded = false
            return
        }
        destroyNodeProcess()
        usingEmbedded = true
        EmbeddedEngineIoServer.start(activePort)
    }

    private fun stopServerLocked() {
        if (usingEmbedded) {
            EmbeddedEngineIoServer.stop()
            usingEmbedded = false
        }
        destroyNodeProcess()
    }

    private fun awaitReadyLocked(timeoutMs: Long = NODE_START_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: String? = null
        while (System.currentTimeMillis() < deadline) {
            if (isAcceptingConnections()) {
                return
            }
            lastError = "polling handshake not ready on port $port"
            Thread.sleep(READY_POLL_INTERVAL_MS)
        }
        throw IllegalStateException(
            "Socket test server not ready after ${timeoutMs}ms ($lastError)",
        )
    }

    private const val PORT_RELEASE_TIMEOUT_MS = 5_000L

    private fun waitForPortReleased(port: Int, timeoutMs: Long = PORT_RELEASE_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isPortInUse(port)) {
                return
            }
            Thread.sleep(READY_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Port $port still in use after ${timeoutMs}ms")
    }

    private fun isPortInUse(port: Int): Boolean = try {
        val url = URI("http://localhost:$port/socket.io/?EIO=4&transport=polling").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        connection.readTimeout = 500
        connection.requestMethod = "GET"
        connection.connect()
        connection.responseCode
        connection.disconnect()
        true
    } catch (_: Exception) {
        false
    }

    private fun isAcceptingConnections(): Boolean {
        if (activePort == 0) return false
        if (!usingEmbedded && nodeProcess?.isAlive != true) {
            return false
        }
        return try {
            val url = URI("http://localhost:$port/socket.io/?EIO=4&transport=polling").toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun tryStartNodeServer(port: Int): Boolean {
        val script = locateSocketServerScript() ?: return false
        val nodeModules = script.parentFile.resolve("node_modules")
        if (!nodeModules.isDirectory) {
            return false
        }

        val nodeReady = CountDownLatch(1)
        return try {
            val process = ProcessBuilder("node", script.absolutePath, "/")
                .directory(script.parentFile)
                .redirectErrorStream(true)
                .apply {
                    environment()["PORT"] = port.toString()
                    if (System.getenv("SOCKET_IO_RECOVERY") == "1") {
                        environment()["RECOVERY"] = "1"
                    }
                }
                .start()
            nodeProcess = process
            serverOutputThread = thread(start = true, isDaemon = true, name = "socket-server-jvm-test-stdout") {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.contains("Socket.IO server listening on port $port")) {
                            nodeReady.countDown()
                        }
                    }
                }
            }
            val logReady = nodeReady.await(NODE_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!logReady || !process.isAlive) {
                destroyNodeProcess()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            destroyNodeProcess()
            false
        }
    }

    private fun destroyNodeProcess() {
        serverOutputThread?.interrupt()
        nodeProcess?.let { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
            }
        }
        nodeProcess = null
        serverOutputThread?.join(1_000)
        serverOutputThread = null
    }

    private fun locateSocketServerScript(): File? {
        val classLoader = Thread.currentThread().contextClassLoader
        val resource = classLoader.getResource("socket-server.js") ?: return null
        return when (resource.protocol) {
            "file" -> File(resource.toURI())
            else -> null
        }
    }
}

internal fun allocateEphemeralPort(): Int = ServerSocket(0).use { it.localPort }
