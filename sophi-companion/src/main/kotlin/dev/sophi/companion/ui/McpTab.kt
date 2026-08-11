package dev.sophi.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.mcp.config.McpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private sealed class McpEditingState {
    object New : McpEditingState()
    data class Existing(val config: McpServerConfig) : McpEditingState()
}

@Composable
fun McpTab(runtime: CompanionRuntime) {
    var servers by remember { mutableStateOf(listOf<McpServerConfig>()) }
    var editing by remember { mutableStateOf<McpEditingState?>(null) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    fun refresh() { servers = runtime.mcpServers() }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("MCP servers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = McpEditingState.New }) { Text("+ Add server") }
        }
        LazyColumn {
            items(servers) { server ->
                ListItem(
                    headlineContent = { Text(server.name) },
                    supportingContent = { Text("${server.transport}") },
                    trailingContent = {
                        Row {
                            Switch(
                                checked = server.enabled,
                                onCheckedChange = { checked -> scope.launch { runtime.setMcpServerEnabled(server.name, checked); refresh() } }
                            )
                            TextButton(onClick = { scope.launch { runtime.removeMcpServer(server.name); refresh() } }) {
                                Text("Remove")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clickable { editing = McpEditingState.Existing(server) }
                )
            }
        }
    }

    editing?.let { state ->
        AddEditMcpServerDialog(
            existing = (state as? McpEditingState.Existing)?.config,
            existingNames = servers.map { it.name }.toSet(),
            onSave = { config ->
                scope.launch { runtime.addOrUpdateMcpServer(config); refresh() }
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}
