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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionSettings
import dev.sophi.companion.LlmProfile
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
    onMaxTokensChange: (String) -> Unit,
    requestTimeoutSeconds: String,
    onRequestTimeoutSecondsChange: (String) -> Unit
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

        OutlinedTextField(
            value = requestTimeoutSeconds,
            onValueChange = onRequestTimeoutSecondsChange,
            label = { Text("Request timeout (seconds)") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "How long to wait for the next chunk of a response before giving up. Local reasoning " +
                "models can spend well over a minute \"thinking\" before producing any output — too " +
                "short a value cuts the request off mid-generation instead of one that's truly stuck.",
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
 * Memory (embedding) fields — shared by the in-app Settings tab's profile editor. Extracted from
 * what used to be an inline, profile-independent "Memory" section so it can be embedded per
 * profile without duplicating the field list.
 */
@Composable
fun MemoryFieldsForm(
    memoryEnabled: Boolean,
    onMemoryEnabledChange: (Boolean) -> Unit,
    embeddingModel: String,
    onEmbeddingModelChange: (String) -> Unit,
    embeddingBaseUrl: String,
    onEmbeddingBaseUrlChange: (String) -> Unit,
    embeddingApiKey: String,
    onEmbeddingApiKeyChange: (String) -> Unit,
    embeddingDimensions: String,
    onEmbeddingDimensionsChange: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Switch(checked = memoryEnabled, onCheckedChange = onMemoryEnabledChange)
        Text(if (memoryEnabled) "Enabled" else "Disabled", modifier = Modifier.padding(start = 8.dp))
    }
    if (memoryEnabled) {
        OutlinedTextField(
            value = embeddingModel,
            onValueChange = onEmbeddingModelChange,
            label = { Text("Embedding model") },
            placeholder = { Text("nomic-embed-text (Ollama) or text-embedding-3-small (OpenAI)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = embeddingBaseUrl,
            onValueChange = onEmbeddingBaseUrlChange,
            label = { Text("Embedding base URL") },
            placeholder = { Text(OLLAMA_BASE_URL) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = embeddingApiKey,
            onValueChange = onEmbeddingApiKeyChange,
            label = { Text("Embedding API key (optional — blank is fine for a local Ollama/vLLM server)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = embeddingDimensions,
            onValueChange = onEmbeddingDimensionsChange,
            label = { Text("Embedding dimensions") },
            modifier = Modifier.fillMaxWidth()
        )
    }
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
    // Not existing?.activeProfile() — existing may have failed validation precisely because
    // profiles is empty (that's one of the repair scenarios this screen handles), and
    // activeProfile() throws on an empty list. Look it up directly instead.
    val existingProfile = existing?.profiles?.let { profiles ->
        profiles.find { it.name == existing.activeProfileName } ?: profiles.firstOrNull()
    }

    var providerType by remember {
        mutableStateOf(existingProfile?.providerType?.takeIf { it in ProviderTypes.ALL } ?: ProviderTypes.CLAUDE)
    }
    var model by remember { mutableStateOf(existingProfile?.model ?: "claude-sonnet-4-5") }
    var baseUrl by remember { mutableStateOf(existingProfile?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(existingProfile?.apiKey ?: "") }
    var contextWindowTokens by remember { mutableStateOf((existingProfile?.contextWindowTokens ?: 200_000).toString()) }
    var maxTokens by remember { mutableStateOf((existingProfile?.maxTokens ?: 4096).toString()) }
    var requestTimeoutSeconds by remember { mutableStateOf((existingProfile?.requestTimeoutSeconds ?: 300).toString()) }

    // Switching provider resets the fields to that provider's sensible starting values.
    fun selectProvider(type: String) {
        if (type == providerType) return
        providerType = type
        val defaults = defaultsForProvider(type)
        model = defaults.model
        baseUrl = defaults.baseUrl
        contextWindowTokens = defaults.contextWindowTokens
    }

    // Preserves the existing profile's own name when repairing a broken config (fixes that
    // profile in place, leaving any other saved profiles untouched) — defaults to "Default" only
    // for a true first run (existing == null).
    val activeName = existing?.activeProfileName ?: "Default"
    val draftProfile = LlmProfile(
        name = activeName,
        providerType = providerType,
        model = model.trim(),
        baseUrl = baseUrl.trim().ifBlank { null },
        apiKey = apiKey.ifBlank { null },
        contextWindowTokens = contextWindowTokens.trim().toIntOrNull() ?: 0,
        maxTokens = maxTokens.trim().toIntOrNull() ?: 0,
        requestTimeoutSeconds = requestTimeoutSeconds.trim().toIntOrNull() ?: 0,
        memoryEnabled = existingProfile?.memoryEnabled ?: false,
        embeddingModel = existingProfile?.embeddingModel,
        embeddingBaseUrl = existingProfile?.embeddingBaseUrl,
        embeddingApiKey = existingProfile?.embeddingApiKey,
        embeddingDimensions = existingProfile?.embeddingDimensions ?: 1536
    )
    val draft = CompanionSettings(
        // Preserve any custom paths from the existing file rather than resetting them to defaults.
        sessionsDir = existing?.sessionsDir ?: CompanionSettings().sessionsDir,
        mcpConfigPath = existing?.mcpConfigPath ?: CompanionSettings().mcpConfigPath,
        profiles = listOf(draftProfile) + (existing?.profiles?.filterNot { it.name == activeName } ?: emptyList()),
        activeProfileName = activeName
    )
    val error = when {
        contextWindowTokens.trim().toIntOrNull() == null -> "Context window must be a whole number"
        maxTokens.trim().toIntOrNull() == null -> "Max tokens must be a whole number"
        requestTimeoutSeconds.trim().toIntOrNull() == null -> "Request timeout must be a whole number"
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
            onMaxTokensChange = { maxTokens = it },
            requestTimeoutSeconds = requestTimeoutSeconds,
            onRequestTimeoutSecondsChange = { requestTimeoutSeconds = it }
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
