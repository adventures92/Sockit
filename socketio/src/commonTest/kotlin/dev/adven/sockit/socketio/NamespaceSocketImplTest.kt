package dev.adven.sockit.socketio

import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketEvent
import dev.adven.sockit.api.SocketException
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.socketOptions
import dev.adven.sockit.internal.WorkQueue
import dev.adven.sockit.protocol.SocketPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NamespaceSocketImplTest {

    private fun testOptions() = socketOptions {
        transports(Transports.WEBSOCKET)
        reconnection = false
    }

    private fun newNamespace(workQueue: WorkQueue = WorkQueue()): NamespaceSocketImpl {
        val manager = ConnectionManager("ws://localhost:0", testOptions())
        return NamespaceSocketImpl(
            manager = manager,
            namespace = "/",
            options = testOptions(),
            scope = CoroutineScope(SupervisorJob()),
            workQueue = workQueue,
        )
    }

    /** Runs [block] on [workQueue] and suspends until it finishes — mirrors WorkQueueTest's pattern. */
    private suspend fun WorkQueue.runAndAwait(block: () -> Unit) {
        val done = CompletableDeferred<Unit>()
        launch {
            block()
            done.complete(Unit)
        }
        done.await()
    }

    @Test
    fun malformedConnectPayloadDoesNotCrashAndStillConnects() = runTest {
        val workQueue = WorkQueue()
        val ns = newNamespace(workQueue)

        workQueue.runAndAwait {
            ns.onSocketPacket(SocketPacket.Connect(namespace = "/", data = "{\"sid\":\"broken"))
        }

        assertEquals(ConnectionState.Connected, ns.connectionState.value)
        assertNull(ns.id.value)
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun malformedEventPayloadIsDroppedAndLaterEventsStillArrive() = runTest {
        val workQueue = WorkQueue()
        val ns = newNamespace(workQueue)

        // Connect first so events dispatch immediately instead of buffering.
        workQueue.runAndAwait {
            ns.onSocketPacket(SocketPacket.Connect(namespace = "/", data = null))
        }

        workQueue.runAndAwait {
            ns.onSocketPacket(SocketPacket.Event(namespace = "/", data = "[\"broken\",{\"a\":1"))
        }

        workQueue.runAndAwait {
            ns.onSocketPacket(SocketPacket.Event(namespace = "/", data = "[\"pong\",{}]"))
        }

        val result = CompletableDeferred<SocketEvent>()
        val job = GlobalScope.launch {
            ns.events("pong").collect { result.complete(it) }
        }
        val received = result.await()
        job.cancel()

        assertEquals("pong", received.name)
    }

    @Test
    fun malformedAckPayloadFailsThePendingEmitWithAckInsteadOfHanging() = runTest {
        val workQueue = WorkQueue()
        val ns = newNamespace(workQueue)

        var result: Result<SocketEvent>? = null
        // UNDISPATCHED runs synchronously up to emitWithAck's first suspension point (ackData.await()),
        // which happens *after* it has already submitted its ack-registration job to workQueue — so the
        // registration is guaranteed enqueued before we submit the reply below (workQueue is single-writer/FIFO).
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            result = runCatching { ns.emitWithAck("ping").first() }
        }

        workQueue.runAndAwait {
            ns.onSocketPacket(SocketPacket.Ack(namespace = "/", id = 0, data = "[\"broken"))
        }
        collector.join()

        assertFailsWith<SocketException.SendFailed> { result!!.getOrThrow() }
    }
}
