package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.companion.SessionState

@Composable
fun ChatTab(runtime: CompanionRuntime, activeSessionId: String) {
    var input by remember { mutableStateOf("") }
    val state by runtime.sessionState(activeSessionId).collectAsState()
    val history by runtime.sessionMessages(activeSessionId).collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            when (state) {
                SessionState.Idle -> "Idle"
                SessionState.Running -> "Sophi is working…"
                SessionState.NeedsConfirmation -> "Waiting for your confirmation…"
                is SessionState.Error -> "Error: ${(state as SessionState.Error).message}"
            }
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(history) { line -> Text(line) }
        }
        Row {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = state != SessionState.Running
            )
            Button(
                enabled = state != SessionState.Running && input.isNotBlank(),
                onClick = {
                    runtime.sendMessage(activeSessionId, input)
                    input = ""
                }
            ) { Text("Send") }
        }
    }
}
