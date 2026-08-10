package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.companion.SessionState
import dev.sophi.core.session.SessionMeta
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private data class SessionRow(val id: String, val title: String, val isRemote: Boolean)

private fun statusLabel(state: SessionState): String = when (state) {
    SessionState.Idle -> "Idle"
    SessionState.Running -> "Running"
    is SessionState.NeedsConfirmation -> "Needs confirmation"
    is SessionState.Error -> "Error"
}

@Composable
private fun SessionStatusBadge(runtime: CompanionRuntime, sessionId: String, isRemote: Boolean) {
    val state by (if (isRemote) runtime.remoteSessions.stateFlowFor(sessionId) else runtime.sessionState(sessionId))
        .collectAsState()
    Text("[${statusLabel(state)}]" + if (isRemote) " (CLI)" else "")
}

@Composable
fun SessionsTab(runtime: CompanionRuntime, onOpen: (String) -> Unit) {
    var localSessions by remember { mutableStateOf(listOf<SessionMeta>()) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    fun refresh() { localSessions = runtime.sessionManager.list() }

    LaunchedEffect(Unit) { refresh() }

    val localIds = localSessions.map { it.id }.toSet()
    val rows = localSessions.map { SessionRow(it.id, it.title ?: it.id, isRemote = false) } +
        runtime.remoteSessions.remoteSessionIds().filterNot { it in localIds }.map { id ->
            SessionRow(id, runtime.remoteSessions.titleFor(id) ?: id, isRemote = true)
        }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Button(onClick = {
            scope.launch {
                val id = runtime.newSession()
                refresh()
                onOpen(id)
            }
        }) { Text("New session") }

        LazyColumn {
            items(rows) { row ->
                Row(modifier = Modifier.padding(4.dp)) {
                    Text(row.title, modifier = Modifier.weight(1f))
                    SessionStatusBadge(runtime, row.id, row.isRemote)
                    Button(onClick = { onOpen(row.id) }) { Text("Open") }
                    if (!row.isRemote) {
                        Button(onClick = {
                            runtime.sessionManager.rename(row.id, "Renamed session")
                            refresh()
                        }) { Text("Rename") }
                        Button(onClick = {
                            runtime.sessionManager.delete(row.id)
                            refresh()
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
