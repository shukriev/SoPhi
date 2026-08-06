package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionSettings

@Composable
fun FirstRunSettingsScreen(onSaved: (CompanionSettings) -> Unit) {
    var model by remember { mutableStateOf("claude-sonnet-4-5") }
    var apiKey by remember { mutableStateOf("") }

    Column(androidx.compose.ui.Modifier.padding(16.dp)) {
        Text("Welcome to Sophi Companion — set up your provider:")
        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") })
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key (leave blank to use ANTHROPIC_API_KEY)") })
        Button(onClick = {
            onSaved(CompanionSettings(model = model, apiKey = apiKey.ifBlank { null }))
        }) { Text("Save and continue") }
    }
}
