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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.mcp.config.McpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun McpTab(runtime: CompanionRuntime) {
    var servers by remember { mutableStateOf(listOf<McpServerConfig>()) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    fun refresh() { servers = runtime.mcpServers() }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("MCP servers")
        LazyColumn {
            items(servers) { server ->
                Row(modifier = Modifier.padding(4.dp)) {
                    Text("${server.name} (${server.transport}) — ${if (server.enabled) "enabled" else "disabled"}", modifier = Modifier.weight(1f))
                    Button(onClick = { scope.launch { runtime.setMcpServerEnabled(server.name, !server.enabled); refresh() } }) {
                        Text(if (server.enabled) "Disable" else "Enable")
                    }
                    Button(onClick = { scope.launch { runtime.removeMcpServer(server.name); refresh() } }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}
