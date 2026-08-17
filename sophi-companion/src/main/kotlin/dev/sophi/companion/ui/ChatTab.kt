package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.companion.SessionState
import dev.sophi.companion.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ChatTab(runtime: CompanionRuntime, activeSessionId: String, title: String) {
    var input by remember { mutableStateOf("") }
    val scope = remember { CoroutineScope(Dispatchers.Default) }
    val isRemote = runtime.isRemote(activeSessionId)
    val state by (if (isRemote) runtime.remoteSessions.stateFlowFor(activeSessionId) else runtime.sessionState(activeSessionId))
        .collectAsState()
    // Remote (CLI) sessions stream into RemoteSessionRegistry's per-session transcript (built
    // from hub Token/ReasoningToken/ToolCall events); local sessions use CompanionRuntime's own,
    // populated by sendMessage. Different sources, same TranscriptEntry shape.
    val history by (if (isRemote) runtime.remoteSessions.transcriptFor(activeSessionId) else runtime.sessionMessages(activeSessionId))
        .collectAsState()
    val pending = state as? SessionState.NeedsConfirmation
    val listState = rememberLazyListState()
    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.lastIndex) }
    // Keyed by session so switching sessions always starts collapsed again, instead of leaking
    // one session's expanded entry ids into another session's unrelated entry id space.
    val expandedIds = remember(activeSessionId) { mutableStateMapOf<Int, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                when (state) {
                    SessionState.Idle -> "Idle"
                    SessionState.Running -> "Running…"
                    is SessionState.NeedsConfirmation -> "Needs confirmation"
                    is SessionState.Error -> "Error"
                },
                color = statusColor(state, MaterialTheme.colorScheme)
            )
        }
        if (state is SessionState.Error) {
            Text((state as SessionState.Error).message, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(history, key = { it.id }) { entry ->
                TranscriptRow(
                    entry = entry,
                    expanded = expandedIds[entry.id] == true,
                    onToggle = { expandedIds[entry.id] = expandedIds[entry.id] != true }
                )
            }
        }
        if (pending != null) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Sophi wants to run: " + pending.requests.joinToString(", ") { "${it.toolName} (${it.riskLevel})" })
                    Row {
                        Button(onClick = {
                            if (isRemote) {
                                scope.launch { pending.requests.forEach { runtime.respondToRemoteConfirmation(activeSessionId, it.callId, true) } }
                            } else {
                                runtime.respondToConfirmation(activeSessionId, true)
                            }
                        }) { Text("Approve") }
                        Button(onClick = {
                            if (isRemote) {
                                scope.launch { pending.requests.forEach { runtime.respondToRemoteConfirmation(activeSessionId, it.callId, false) } }
                            } else {
                                runtime.respondToConfirmation(activeSessionId, false)
                            }
                        }) { Text("Deny") }
                    }
                }
            }
        }
        Row {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = state != SessionState.Running && state !is SessionState.NeedsConfirmation
            )
            Button(
                enabled = state != SessionState.Running && state !is SessionState.NeedsConfirmation && input.isNotBlank(),
                onClick = {
                    if (isRemote) {
                        scope.launch { runtime.sendRemoteMessage(activeSessionId, input) }
                    } else {
                        runtime.sendMessage(activeSessionId, input)
                    }
                    input = ""
                }
            ) { Text("Send") }
        }
    }
}

@Composable
private fun TranscriptRow(entry: TranscriptEntry, expanded: Boolean, onToggle: () -> Unit) {
    when (entry) {
        is TranscriptEntry.UserMessage -> Text(
            "you: ${entry.text}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        is TranscriptEntry.Answer -> Text(
            "sophi: ${entry.text}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        is TranscriptEntry.Reasoning -> CollapsibleCard(
            expanded = expanded,
            onToggle = onToggle,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
            summary = "Thinking: " + entry.text.take(80) + if (entry.text.length > 80) "…" else "",
            full = entry.text,
        )
        is TranscriptEntry.ToolInvocation -> CollapsibleCard(
            expanded = expanded,
            onToggle = onToggle,
            container = when {
                entry.isError -> MaterialTheme.colorScheme.errorContainer
                entry.result != null -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            onContainer = if (entry.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            summary = "${entry.name}(${entry.argsJson.take(60)}) → " +
                if (entry.result == null) "…" else if (entry.isError) "error" else "ok",
            full = "args: ${entry.argsJson}\nresult: ${entry.result ?: "(running)"}",
        )
    }
}
