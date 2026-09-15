package dev.adven.socketdemo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketClient
import dev.adven.sockit.api.SocketPayload
import dev.adven.sockit.api.Subscribe
import dev.adven.sockit.api.Transports
import dev.adven.sockit.api.Unsubscribe
import dev.adven.sockit.api.socketOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DemoViewModel : ViewModel() {
    private var client: SocketClient? = null
    private var connectionStateJob: Job? = null
    private val subscriptionJobs = mutableMapOf<String, Job>()
    private val subscribedTopics = mutableSetOf<String>()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError: StateFlow<String?> = _urlError.asStateFlow()

    private val _topicInput = MutableStateFlow("")
    val topicInput: StateFlow<String> = _topicInput.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()

    fun onServerUrlChange(newUrl: String) {
        if (newUrl != _serverUrl.value) {
            _urlError.value = null
        }
        if (newUrl == _serverUrl.value) return
        _serverUrl.value = newUrl
        if (client != null || subscriptionJobs.isNotEmpty()) {
            tearDown(clearLog = true)
        }
    }

    fun onTopicInputChange(topic: String) {
        _topicInput.value = topic
    }

    fun toggleConnection() {
        when (_connectionState.value) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting,
            -> disconnect()

            else -> connect()
        }
    }

    fun connect() {
        if (client != null) return

        val url = _serverUrl.value.trim()
        val validationError = validateServerUrl(url)
        if (validationError != null) {
            _urlError.value = validationError
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val socketClient = SocketClient.connect(
                    url,
                    socketOptions {
                        transports(Transports.WEBSOCKET)
                        trustAllCerts = true
                    },
                )
                client = socketClient
                val socket = socketClient.namespace()

                connectionStateJob?.cancel()
                connectionStateJob = launch {
                    socket.connectionState.collect { state ->
                        _connectionState.value = state
                    }
                }

                socket.openAwait()
            }.onFailure { error ->
                appendLog("Connect failed: ${error.message ?: error::class.simpleName}")
                tearDown(clearLog = false)
            }
        }
    }

    fun disconnect() {
        tearDown(clearLog = false)
    }

    fun subscribeToTopic() {
        val topic = _topicInput.value.trim()
        if (topic.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val socket = client?.namespace() ?: return@launch
            if (!socket.isConnected()) {
                appendLog("Subscribe failed: not connected")
                return@launch
            }

            if (subscribedTopics.add(topic)) {
                socket.emit(
                    Subscribe(buildJsonObject { put("topic", topic) }),
                )
            }

            subscriptionJobs[topic]?.cancel()
            subscriptionJobs[topic] = launch(Dispatchers.IO) {
                socket.events(topic).collect { event ->
                    val payload = event.args.take(500).joinToString(" | ") { formatPayload(it) }
                    appendLog("[$topic] $payload", forceRefresh = true)
                }
            }
        }
    }

    private fun tearDown(clearLog: Boolean) {
        val socket = client?.namespace()
        subscribedTopics.forEach { topic ->
            runCatching {
                socket?.emit(
                    Unsubscribe(buildJsonObject { put("topic", topic) }),
                )
            }
        }
        subscribedTopics.clear()

        subscriptionJobs.values.forEach { it.cancel() }
        subscriptionJobs.clear()

        connectionStateJob?.cancel()
        connectionStateJob = null

        client?.close()
        client = null

        _connectionState.value = ConnectionState.Disconnected
        if (clearLog) {
            _eventLog.value = emptyList()
        }
    }

    private fun appendLog(message: String, forceRefresh: Boolean = false) {
        if (forceRefresh) {
            _eventLog.value = listOf(message)
        } else {
            _eventLog.value += message
        }
    }

    private fun formatPayload(payload: SocketPayload): String = when (payload) {
        is SocketPayload.Text -> payload.value
        is SocketPayload.Json -> payload.element.toString()
        is SocketPayload.Binary -> "binary(${payload.bytes.size} bytes)"
    }

    private fun validateServerUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "Enter a server URL"

        if (!URL_SCHEME.matches(url)) {
            return "URL must start with http://, https://, ws://, or wss://"
        }

        val hostPart = url.substringAfter("://").substringBefore("/").substringBefore("?")
        if (hostPart.isBlank() || hostPart.startsWith(":")) {
            return "URL must include a host (e.g. wss://localhost:3000)"
        }

        return null
    }

    private companion object {
        private val URL_SCHEME = Regex("^(https?|wss?)://.+", RegexOption.IGNORE_CASE)
    }
}
