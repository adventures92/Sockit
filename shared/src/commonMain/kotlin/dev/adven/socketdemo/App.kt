package dev.adven.socketdemo

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.adven.sockit.api.ConnectionState
import dev.adven.sockit.api.SocketError

@Composable
fun App() {
    MaterialTheme {
        val viewModel = viewModel { DemoViewModel() }
        val connectionState by viewModel.connectionState.collectAsState()
        val eventLog by viewModel.eventLog.collectAsState()
        val serverUrl by viewModel.serverUrl.collectAsState()
        val urlError by viewModel.urlError.collectAsState()
        val topicInput by viewModel.topicInput.collectAsState()

        SockitDemoScreen(
            connectionState = connectionState,
            eventLog = eventLog,
            serverUrl = serverUrl,
            urlError = urlError,
            topicInput = topicInput,
            onServerUrlChange = viewModel::onServerUrlChange,
            onTopicInputChange = viewModel::onTopicInputChange,
            onToggleConnection = viewModel::toggleConnection,
            onSubscribe = viewModel::subscribeToTopic,
        )
    }
}

@Composable
fun SockitDemoScreen(
    connectionState: ConnectionState,
    eventLog: List<String>,
    serverUrl: String,
    urlError: String?,
    topicInput: String,
    onServerUrlChange: (String) -> Unit,
    onTopicInputChange: (String) -> Unit,
    onToggleConnection: () -> Unit,
    onSubscribe: () -> Unit,
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting
    val isReconnecting = connectionState is ConnectionState.Reconnecting
    val isUrlEditable = !isConnected
    val canToggleConnection = isConnected || isReconnecting || !isConnecting

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer)
            .safeContentPadding()
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BlinkingStatusText(connectionState = connectionState)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    enabled = isUrlEditable,
                    isError = urlError != null,
                    placeholder = { Text("wss://localhost:3000") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.defaultMinSize(minHeight = 56.dp),
                    onClick = onToggleConnection,
                    enabled = canToggleConnection,
                ) {
                    Text(
                        when {
                            isConnected || isReconnecting -> "Disconnect"
                            isConnecting -> "Connecting"
                            else -> "Connect"
                        },
                    )
                }
            }
            if (urlError != null) {
                Text(
                    text = urlError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = topicInput,
                onValueChange = onTopicInputChange,
                enabled = isConnected,
                label = { Text("Topic / event name") },
                placeholder = { Text("Topic / event name") },
                singleLine = true,
            )
            Button(
                modifier = Modifier.defaultMinSize(minHeight = 56.dp),
                onClick = onSubscribe,
                enabled = isConnected && topicInput.isNotBlank(),
            ) {
                Text("Subscribe")
            }
        }

        Text(
            text = "Incoming events",
            style = MaterialTheme.typography.titleSmall,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (eventLog.isEmpty()) {
                Text(
                    text = "No events yet. Connect and subscribe to a topic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                eventLog.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlinkingStatusText(connectionState: ConnectionState) {
    val shouldBlink = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Reconnecting

    val infiniteTransition = rememberInfiniteTransition(label = "statusBlink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (shouldBlink) 0.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusAlpha",
    )

    Text(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        text = connectionStateLabel(connectionState),
        style = MaterialTheme.typography.titleMedium,
        color = connectionStateColor(connectionState),
    )
}

private fun connectionStateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> "Status: Disconnected"
    ConnectionState.Connecting -> "Status: Connecting…"
    ConnectionState.Connected -> "Status: Connected"
    is ConnectionState.Reconnecting -> "Status: Reconnecting (attempt ${state.attempt})…"
    is ConnectionState.Failed -> "Status: Failed — ${formatSocketError(state.error)}"
}

@Composable
private fun connectionStateColor(state: ConnectionState) = when (state) {
    ConnectionState.Connected -> MaterialTheme.colorScheme.primary
    is ConnectionState.Failed -> MaterialTheme.colorScheme.error
    ConnectionState.Connecting, is ConnectionState.Reconnecting -> MaterialTheme.colorScheme.tertiary
    ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatSocketError(error: SocketError): String = when (error) {
    is SocketError.Timeout -> "Timeout (${error.phase})"
    is SocketError.TlsFailure -> "TLS failure"
    is SocketError.ParseError -> "Parse error"
    SocketError.PingTimeout -> "Ping timeout"
    is SocketError.TransportClosed -> error.reason ?: "Transport closed"
    is SocketError.SendFailed -> "Send failed"
    is SocketError.ConnectError -> error.message
}
