package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime

@Composable
fun PendingConfirmationsBanner(runtime: CompanionRuntime, activeSessionId: String, onJump: (String) -> Unit) {
    val pending by runtime.pendingConfirmations.collectAsState()
    val otherPending = (pending - activeSessionId).toList()
    if (otherPending.isEmpty()) return

    val titleById = remember(otherPending) {
        runtime.sessionManager.list().associate { it.id to (it.title ?: it.id) }
    }

    Column {
        otherPending.forEach { sessionId ->
            Row(modifier = Modifier.padding(4.dp)) {
                Text("${titleById[sessionId] ?: sessionId} needs confirmation", modifier = Modifier.weight(1f))
                Button(onClick = { onJump(sessionId) }) { Text("Go") }
            }
        }
    }
}
