package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timestampFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

private fun formatTimestamp(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timestampFormatter)

@Composable
fun NotificationsTab(runtime: CompanionRuntime) {
    val notifications by runtime.notificationCenter.records.collectAsState()

    LaunchedEffect(Unit) { runtime.notificationCenter.markAllRead() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Notifications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { runtime.notificationCenter.clear() }, enabled = notifications.isNotEmpty()) {
                Text("Clear")
            }
        }
        if (notifications.isEmpty()) {
            Text("No notifications yet.", style = MaterialTheme.typography.bodyMedium)
        }
        SelectionContainer {
            LazyColumn {
                items(notifications, key = { it.id }) { record ->
                    ListItem(
                        headlineContent = { Text(record.title) },
                        supportingContent = { Text(record.body) },
                        trailingContent = {
                            Text(formatTimestamp(record.timestampMs), style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
