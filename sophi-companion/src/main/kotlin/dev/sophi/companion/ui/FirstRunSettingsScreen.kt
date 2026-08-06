package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionSettings
import dev.sophi.companion.ProviderTypes
import dev.sophi.companion.validationError

private const val OLLAMA_BASE_URL = "http://localhost:11434/v1"

/**
 * Provider setup. Shown on first run, and also when an existing `~/.sophi/companion.json`
 * fails validation — [existing] pre-fills the form and [problem] explains what was wrong, so a
 * broken config can be repaired in-app instead of dead-ending on a startup error.
 */
@Composable
fun FirstRunSettingsScreen(
    onSaved: (CompanionSettings) -> Unit,
    existing: CompanionSettings? = null,
    problem: String? = null
) {
    var providerType by remember {
        mutableStateOf(existing?.providerType?.takeIf { it in ProviderTypes.ALL } ?: ProviderTypes.CLAUDE)
    }
    var model by remember { mutableStateOf(existing?.model ?: "claude-sonnet-4-5") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
    var contextWindowTokens by remember { mutableStateOf((existing?.contextWindowTokens ?: 200_000).toString()) }
    var maxTokens by remember { mutableStateOf((existing?.maxTokens ?: 4096).toString()) }

    // Switching provider resets the fields whose sensible value differs by provider — a Claude
    // context window on a local model would disable compaction entirely (see CompanionSettings).
    fun selectProvider(type: String) {
        if (type == providerType) return
        providerType = type
        if (type == ProviderTypes.CLAUDE) {
            model = "claude-sonnet-4-5"
            baseUrl = ""
            contextWindowTokens = "200000"
        } else {
            model = "qwen3:8b"
            baseUrl = OLLAMA_BASE_URL
            contextWindowTokens = "32768"
        }
    }

    val isLocal = providerType == ProviderTypes.OPENAI_COMPAT
    val draft = CompanionSettings(
        providerType = providerType,
        model = model.trim(),
        baseUrl = baseUrl.trim().ifBlank { null },
        apiKey = apiKey.ifBlank { null },
        contextWindowTokens = contextWindowTokens.trim().toIntOrNull() ?: 0,
        maxTokens = maxTokens.trim().toIntOrNull() ?: 0,
        // Preserve any custom paths from the existing file rather than resetting them to defaults.
        sessionsDir = existing?.sessionsDir ?: CompanionSettings().sessionsDir,
        mcpConfigPath = existing?.mcpConfigPath ?: CompanionSettings().mcpConfigPath
    )
    val error = when {
        contextWindowTokens.trim().toIntOrNull() == null -> "Context window must be a whole number"
        maxTokens.trim().toIntOrNull() == null -> "Max tokens must be a whole number"
        else -> draft.validationError()
    }

    Column(
        modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            if (problem == null) "Welcome to Sophi Companion" else "Fix your Sophi Companion settings",
            style = MaterialTheme.typography.h6
        )
        if (problem != null) {
            Text("Your saved settings can't be used: $problem", color = MaterialTheme.colors.error)
        }
        Text("Set up the model you want Sophi to use. You can change this later in ~/.sophi/companion.json.")
        Spacer(Modifier.height(12.dp))

        Text("Provider")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = providerType == ProviderTypes.CLAUDE,
                onClick = { selectProvider(ProviderTypes.CLAUDE) }
            )
            Text("Claude (Anthropic API)", modifier = Modifier.padding(end = 16.dp))
            RadioButton(
                selected = isLocal,
                onClick = { selectProvider(ProviderTypes.OPENAI_COMPAT) }
            )
            Text("Local / OpenAI-compatible")
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            modifier = Modifier.fillMaxWidth()
        )

        if (isLocal) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text(OLLAMA_BASE_URL) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Ollama: $OLLAMA_BASE_URL   ·   vLLM: http://localhost:8000/v1",
                style = MaterialTheme.typography.caption
            )
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = {
                Text(
                    if (isLocal) "API key (optional — blank is correct for Ollama/vLLM)"
                    else "API key (leave blank to use ANTHROPIC_API_KEY)"
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = contextWindowTokens,
            onValueChange = { contextWindowTokens = it },
            label = { Text("Context window (tokens)") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Your model's real context window. Sophi compacts at 80% of this — setting it larger " +
                "than the model actually supports means it never compacts and overflows instead.",
            style = MaterialTheme.typography.caption
        )

        OutlinedTextField(
            value = maxTokens,
            onValueChange = { maxTokens = it },
            label = { Text("Max tokens per response") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        if (error != null) {
            Text(error, color = MaterialTheme.colors.error)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            enabled = error == null,
            onClick = { onSaved(draft) }
        ) { Text("Save and continue") }
    }
}
