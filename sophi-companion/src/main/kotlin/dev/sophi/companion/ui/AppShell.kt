package dev.sophi.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.companion.SessionState
import dev.sophi.core.session.SessionMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private sealed class Selection {
    data class Session(val sessionId: String) : Selection()
    object Mcp : Selection()
    object Goals : Selection()
}

private data class SidebarSource(val id: String, val title: String, val isRemote: Boolean, val lastActiveMillis: Long)

@Composable
fun AppShell(runtime: CompanionRuntime) {
    var selected by remember { mutableStateOf<Selection?>(null) }
    var localSessions by remember { mutableStateOf(listOf<SessionMeta>()) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    fun refreshSessions() { localSessions = runtime.sessionManager.list() }

    LaunchedEffect(Unit) {
        refreshSessions()
        if (selected == null) {
            val id = runtime.newSession()
            refreshSessions()
            selected = Selection.Session(id)
        }
    }

    val localIds = localSessions.map { it.id }.toSet()
    val remoteIds = runtime.remoteSessions.remoteSessionIds().filterNot { it in localIds }
    val base = localSessions.map { SidebarSource(it.id, it.title ?: it.id, false, it.lastModifiedMillis) } +
        remoteIds.map { id ->
            SidebarSource(id, runtime.remoteSessions.titleFor(id) ?: id, true, runtime.remoteSessions.lastActiveMillisFor(id))
        }

    val statesFlow = remember(base.map { it.id }) {
        if (base.isEmpty()) flowOf(emptyList())
        else combine(base.map { source ->
            if (source.isRemote) runtime.remoteSessions.stateFlowFor(source.id) else runtime.sessionState(source.id)
        }) { it.toList() }
    }
    val states by statesFlow.collectAsState(initial = base.map { SessionState.Idle })
    val rows = sortForSidebar(
        base.mapIndexed { i, source ->
            SessionRowData(source.id, source.title, source.isRemote, states.getOrElse(i) { SessionState.Idle }, source.lastActiveMillis)
        }
    )

    Row(modifier = Modifier.fillMaxSize()) {
        Sidebar(
            rows = rows,
            selected = selected,
            onSelectSession = { selected = Selection.Session(it) },
            onSelectMcp = { selected = Selection.Mcp },
            onSelectGoals = { selected = Selection.Goals },
            onNewSession = {
                scope.launch {
                    val id = runtime.newSession()
                    refreshSessions()
                    selected = Selection.Session(id)
                }
            },
            onRename = { id, title -> runtime.sessionManager.rename(id, title); refreshSessions() },
            onDelete = { id ->
                runtime.sessionManager.delete(id)
                refreshSessions()
                val wasSelected = selected.let { it is Selection.Session && it.sessionId == id }
                if (wasSelected) {
                    val remaining = runtime.sessionManager.list()
                    if (remaining.isNotEmpty()) {
                        selected = Selection.Session(remaining.first().id)
                    } else {
                        scope.launch {
                            val newId = runtime.newSession()
                            refreshSessions()
                            selected = Selection.Session(newId)
                        }
                    }
                }
            },
            modifier = Modifier.width(240.dp).fillMaxHeight()
        )
        VerticalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (val s = selected) {
                is Selection.Session -> {
                    val title = rows.find { it.id == s.sessionId }?.title ?: s.sessionId
                    ChatTab(runtime, s.sessionId, title)
                }
                Selection.Mcp -> McpTab(runtime)
                Selection.Goals -> GoalsTab(runtime)
                null -> Text("Starting…")
            }
        }
    }
}

@Composable
private fun Sidebar(
    rows: List<SessionRowData>,
    selected: Selection?,
    onSelectSession: (String) -> Unit,
    onSelectMcp: () -> Unit,
    onSelectGoals: () -> Unit,
    onNewSession: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp)) {
            Image(painterResource("icons/logo.png"), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sophi", style = MaterialTheme.typography.titleMedium)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(rows, key = { it.id }) { row ->
                SessionRow(
                    row = row,
                    selected = selected is Selection.Session && selected.sessionId == row.id,
                    onClick = { onSelectSession(row.id) },
                    onRename = { newTitle -> onRename(row.id, newTitle) },
                    onDelete = { onDelete(row.id) }
                )
            }
        }

        TextButton(onClick = onNewSession, modifier = Modifier.fillMaxWidth()) { Text("+ New session") }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        NavRow(label = "MCP", selected = selected == Selection.Mcp, onClick = onSelectMcp)
        NavRow(label = "Goals", selected = selected == Selection.Goals, onClick = onSelectGoals)
    }
}

@Composable
private fun NavRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SessionRow(
    row: SessionRowData,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var renaming by remember(row.id) { mutableStateOf(false) }
    var draftTitle by remember(row.id) { mutableStateOf(row.title) }
    var menuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val isFallbackTitle = row.title == row.id

    val rowBackground = when {
        row.state is SessionState.Error || row.state is SessionState.NeedsConfirmation ->
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(Modifier.size(7.dp).background(statusColor(row.state, MaterialTheme.colorScheme), CircleShape))
        Spacer(Modifier.width(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (renaming) {
                TextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused && renaming) {
                                onRename(draftTitle)
                                renaming = false
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                onRename(draftTitle)
                                renaming = false
                                true
                            } else false
                        }
                )
            } else if (isFallbackTitle) {
                Text(
                    row.title.take(8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (row.isRemote) {
            Text("CLI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Box {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable { menuExpanded = true }
                ) {
                    Text("⋮", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menuExpanded = false
                        draftTitle = if (isFallbackTitle) "" else row.title
                        renaming = true
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menuExpanded = false
                        onDelete()
                    })
                }
            }
        }
    }
    LaunchedEffect(renaming) { if (renaming) focusRequester.requestFocus() }
}
