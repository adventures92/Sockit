package dev.adven.sockit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class WorkQueue {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1, "socketio-worker"),
    )

    fun launch(block: suspend () -> Unit): Job = scope.launch { block() }
}
