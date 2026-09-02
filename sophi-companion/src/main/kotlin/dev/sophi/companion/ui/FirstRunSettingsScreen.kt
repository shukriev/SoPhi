package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionSettings
import dev.sophi.companion.ProviderTypes
import dev.sophi.companion.providerDisplayName
import dev.sophi.companion.validationError

const val OLLAMA_BASE_URL = "http://localhost:11434/v1"

/** Sensible starting values for [ProviderFieldsForm] when a form switches to provider [type] — a
 *  Claude context window on a small model would disable compaction entirely (see CompanionSettings).
 *  These are just a starting point: the endpoint itself can be local (Ollama/vLLM) or a remote
 *  hosted API (OpenAI, Together, etc.) — "OpenAI-compatible" describes the wire protocol, not where
 *  the server runs. */
data class ProviderDefaults(val model: String, val baseUrl: String, val contextWindowTokens: String)

fun defaultsForProvider(type: String): ProviderDefaults = if (type == ProviderTypes.CLAUDE)
    ProviderDefaults(model = "claude-sonnet-4-5", baseUrl = "", contextWindowTokens = "200000")
else
    ProviderDefaults(model = "qwen3:8b", baseUrl = OLLAMA_BASE_URL, contextWindowTokens = "32768")

/**
 * Provider dropdown + model/baseUrl/apiKey/token fields, shared by [FirstRunSettingsScreen] and the
 * in-app Settings tab's editable "Model" section so the two don't drift out of sync. The dropdown
 * lists [ProviderTypes.ALL] generically — adding a third provider type later is a one-line change
 * to that set plus [providerDisplayName], not a new hardcoded control here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderFieldsForm(
    providerType: String,
    onProviderTypeChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    contextWindowTokens: String,
    onContextWindowTokensChange: (String) -> Unit,
    maxTokens: String,
    onMaxTokensChange: (String) -> Unit
) {
    val isLocal = providerType == ProviderTypes.OPENAI_COMPAT
    var providerMenuExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = providerMenuExpanded,
        onExpandedChange = { providerMenuExpanded = it }
    ) {
        OutlinedTextField(
            value = providerDisplayName(providerType),
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = providerMenuExpanded,
            onDismissRequest = { providerMenuExpanded = false }
        ) {
            ProviderTypes.ALL.forEach { type ->
                DropdownMenuItem(
                    text = { Text(providerDisplayName(type)) },
                    onClick = {
                        providerMenuExpanded = false
                        onProviderTypeChange(type)
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = model,
        onValueChange = onModelChange,
        label = { Text("Model") },
        modifier = Modifier.fillMaxWidth()
    )

    if (isLocal) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("Base URL") },
            placeholder = { Text(OLLAMA_BASE_URL) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Local: Ollama $OLLAMA_BASE_URL, vLLM http://localhost:8000/v1   ·   " +
                "Hosted: OpenAI https://api.openai.com/v1, or any other OpenAI-compatible endpoint",
            style = MaterialTheme.typography.bodySmall
        )
    }

    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = {
            Text(
                if (isLocal) "API key (blank is fine for a local Ollama/vLLM server; required for most hosted endpoints)"
                else "API key (leave blank to use ANTHROPIC_API_KEY)"
            )
        },
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = contextWindowTokens,
        onValueChange = onContextWindowTokensChange,
        label = { Text("Context window (tokens)") },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Your model's real context window. Sophi compacts at 80% of this — setting it larger " +
            "than the model actually supports means it never compacts and overflows instead.",
        style = MaterialTheme.typography.bodySmall
    )

    OutlinedTextField(
        value = maxTokens,
        onValueChange = onMaxTokensChange,
        label = { Text("Max tokens per response") },
        modifier = Modifier.fillMaxWidth()
    )
}

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

    // Switching provider resets the fields to that provider's sensible starting values.
    fun selectProvider(type: String) {
        if (type == providerType) return
        providerType = type
        val defaults = defaultsForProvider(type)
        model = defaults.model
        baseUrl = defaults.baseUrl
        contextWindowTokens = defaults.contextWindowTokens
    }

    val draft = CompanionSettings(
        providerType = providerType,
        model = model.trim(),
        baseUrl = baseUrl.trim().ifBlank { null },
        apiKey = apiKey.ifBlank { null },
        contextWindowTokens = contextWindowTokens.trim().toIntOrNull() ?: 0,
        maxTokens = maxTokens.trim().toIntOrNull() ?: 0,
        // Preserve any custom paths from the existing file rather than resetting them to defaults.
        sessionsDir = existing?.sessionsDir ?: CompanionSettings().sessionsDir,
        mcpConfigPath = existing?.mcpConfigPath ?: CompanionSettings().mcpConfigPath,
        profiles = existing?.profiles ?: emptyList()
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
            style = MaterialTheme.typography.titleLarge
        )
        if (problem != null) {
            Text("Your saved settings can't be used: $problem", color = MaterialTheme.colorScheme.error)
        }
        Text("Set up the model you want Sophi to use. You can change this later from the Settings tab.")
        Spacer(Modifier.height(12.dp))

        ProviderFieldsForm(
            providerType = providerType,
            onProviderTypeChange = ::selectProvider,
            model = model,
            onModelChange = { model = it },
            baseUrl = baseUrl,
            onBaseUrlChange = { baseUrl = it },
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            contextWindowTokens = contextWindowTokens,
            onContextWindowTokensChange = { contextWindowTokens = it },
            maxTokens = maxTokens,
            onMaxTokensChange = { maxTokens = it }
        )

        Spacer(Modifier.height(12.dp))
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            enabled = error == null,
            onClick = { onSaved(draft) }
        ) { Text("Save and continue") }
    }
}
