package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditMcpServerDialog(
    existing: McpServerConfig?,
    existingNames: Set<String>,
    onSave: (McpServerConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var transport by remember { mutableStateOf(existing?.transport ?: McpTransport.STDIO) }
    var commandText by remember { mutableStateOf(existing?.command?.joinToString(" ") ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

    val error = mcpFormError(name, transport, commandText, url, existingNames, existing?.name)

    // material3-desktop 1.8.2 (this project's resolved version) only exposes AlertDialog's
    // plain content-lambda overload — not the title/text/confirmButton/dismissButton
    // convenience overload some other Compose Multiplatform versions add (verified against
    // the resolved jar) — and that overload is itself deprecated in favor of BasicAlertDialog,
    // same shape. Title/fields/buttons are built by hand inside content either way.
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp).width(360.dp)) {
                Text(
                    if (existing == null) "Add MCP server" else "Edit MCP server",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = transport == McpTransport.STDIO, onClick = { transport = McpTransport.STDIO })
                    Text("stdio", modifier = Modifier.padding(end = 16.dp))
                    RadioButton(selected = transport == McpTransport.HTTP, onClick = { transport = McpTransport.HTTP })
                    Text("http")
                }
                Spacer(Modifier.height(8.dp))
                if (transport == McpTransport.STDIO) {
                    OutlinedTextField(
                        value = commandText, onValueChange = { commandText = it },
                        label = { Text("Command") },
                        placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem /path") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("URL") }, modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("Enabled")
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = error == null,
                        onClick = {
                            onSave(
                                McpServerConfig(
                                    name = name.trim(),
                                    transport = transport,
                                    command = if (transport == McpTransport.STDIO) commandText.trim().split(Regex("\\s+")) else emptyList(),
                                    env = existing?.env ?: emptyMap(),
                                    url = if (transport == McpTransport.HTTP) url.trim() else null,
                                    safeTools = existing?.safeTools ?: emptyList(),
                                    enabled = enabled
                                )
                            )
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}
