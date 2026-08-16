package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime

@Composable
fun NotificationsTab(runtime: CompanionRuntime) {
    val notifications by runtime.notificationCenter.records.collectAsState()

    LaunchedEffect(Unit) { runtime.notificationCenter.markAllRead() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        if (notifications.isEmpty()) {
            Text("No notifications yet.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn {
            items(notifications, key = { it.id }) { record ->
                ListItem(
                    headlineContent = { Text(record.title) },
                    supportingContent = { Text(record.body) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
