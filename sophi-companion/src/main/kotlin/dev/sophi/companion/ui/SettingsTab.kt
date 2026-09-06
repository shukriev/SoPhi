package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionSettings
import dev.sophi.companion.LlmProfile
import dev.sophi.companion.ProviderTypes
import dev.sophi.companion.applyProfile
import dev.sophi.companion.providerDisplayName
import dev.sophi.companion.validationError
import dev.sophi.companion.voice.InstallState
import dev.sophi.companion.voice.VoiceInstaller
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsTab(
    settings: CompanionSettings,
    onSettingsChanged: (CompanionSettings) -> Unit,
    voiceInstaller: VoiceInstaller
) {
    val installState by voiceInstaller.state.collectAsState()
    var isInstalled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // isInstalled() is the read-only, network-free check — used once on entry so each row shows
    // "Installed"/"Not installed" correctly without ever calling install() just to find out.
    LaunchedEffect(Unit) { isInstalled = voiceInstaller.isInstalled() }

    val installBusy = installState is InstallState.Downloading ||
        installState is InstallState.Verifying ||
        installState is InstallState.Extracting ||
        installState is InstallState.CheckingExisting

    // Both rows share one VoiceInstaller (STT/TTS install together as one bundle regardless of
    // which was flipped on) — install() is a no-op if already running/installed. Only the flag
    // for the row actually clicked gets set on success, so enabling one doesn't silently enable
    // the other.
    fun enable(apply: (CompanionSettings) -> CompanionSettings) {
        scope.launch {
            voiceInstaller.install()
            val result = voiceInstaller.state.first { it is InstallState.Ready || it is InstallState.Error }
            isInstalled = voiceInstaller.isInstalled()
            if (result is InstallState.Ready) onSettingsChanged(apply(settings))
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("Active connection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))

        // Keyed on `settings` (not Unit) so a profile Use/Delete elsewhere in this tab — which
        // replaces `settings` via onSettingsChanged — refreshes these drafts. Typing here only
        // touches the local vars, so it doesn't retrigger this block on every keystroke.
        var providerType by remember(settings) { mutableStateOf(settings.providerType) }
        var model by remember(settings) { mutableStateOf(settings.model) }
        var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl ?: "") }
        var apiKey by remember(settings) { mutableStateOf(settings.apiKey ?: "") }
        var contextWindowTokens by remember(settings) { mutableStateOf(settings.contextWindowTokens.toString()) }
        var maxTokens by remember(settings) { mutableStateOf(settings.maxTokens.toString()) }
        var requestTimeoutSeconds by remember(settings) { mutableStateOf(settings.requestTimeoutSeconds.toString()) }

        val draft = settings.copy(
            providerType = providerType,
            model = model.trim(),
            baseUrl = baseUrl.trim().ifBlank { null },
            apiKey = apiKey.ifBlank { null },
            contextWindowTokens = contextWindowTokens.trim().toIntOrNull() ?: 0,
            maxTokens = maxTokens.trim().toIntOrNull() ?: 0,
            requestTimeoutSeconds = requestTimeoutSeconds.trim().toIntOrNull() ?: 0
        )
        val draftError = when {
            contextWindowTokens.trim().toIntOrNull() == null -> "Context window must be a whole number"
            maxTokens.trim().toIntOrNull() == null -> "Max tokens must be a whole number"
            requestTimeoutSeconds.trim().toIntOrNull() == null -> "Request timeout must be a whole number"
            else -> draft.validationError()
        }

        ProviderFieldsForm(
            providerType = providerType,
            onProviderTypeChange = { providerType = it },
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
        if (draftError != null) {
            Text(draftError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            modifier = Modifier.padding(top = 8.dp),
            enabled = draftError == null,
            onClick = { onSettingsChanged(draft) }
        ) { Text("Apply") }

        Text("Saved profiles", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Each profile is a name plus its own full connection setup — switch the active " +
                "connection to one with a click, without disturbing the others.",
            style = MaterialTheme.typography.bodySmall
        )
        settings.profiles.forEach { profile ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    "${profile.name} — ${providerDisplayName(profile.providerType)} / ${profile.model}",
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onSettingsChanged(settings.applyProfile(profile)) }) { Text("Use") }
                TextButton(onClick = {
                    onSettingsChanged(settings.copy(profiles = settings.profiles.filterNot { it.name == profile.name }))
                }) { Text("Delete") }
            }
        }

        // A standalone form, independent of the "Active connection" drafts above — adding a
        // profile here never touches what's currently active.
        var addingProfile by remember { mutableStateOf(false) }
        if (addingProfile) {
            var newName by remember { mutableStateOf("") }
            var newProviderType by remember { mutableStateOf(ProviderTypes.CLAUDE) }
            val newDefaults = remember { defaultsForProvider(ProviderTypes.CLAUDE) }
            var newModel by remember { mutableStateOf(newDefaults.model) }
            var newBaseUrl by remember { mutableStateOf(newDefaults.baseUrl) }
            var newApiKey by remember { mutableStateOf("") }
            var newContextWindowTokens by remember { mutableStateOf(newDefaults.contextWindowTokens) }
            var newMaxTokens by remember { mutableStateOf("4096") }
            var newRequestTimeoutSeconds by remember { mutableStateOf("300") }

            val newProfileDraft = LlmProfile(
                name = newName.trim(),
                providerType = newProviderType,
                model = newModel.trim(),
                baseUrl = newBaseUrl.trim().ifBlank { null },
                apiKey = newApiKey.ifBlank { null },
                contextWindowTokens = newContextWindowTokens.trim().toIntOrNull() ?: 0,
                maxTokens = newMaxTokens.trim().toIntOrNull() ?: 0,
                requestTimeoutSeconds = newRequestTimeoutSeconds.trim().toIntOrNull() ?: 0
            )
            val newProfileError = when {
                newName.isBlank() -> "Name must not be blank"
                settings.profiles.any { it.name == newProfileDraft.name } -> "A profile named '${newProfileDraft.name}' already exists"
                newContextWindowTokens.trim().toIntOrNull() == null -> "Context window must be a whole number"
                newMaxTokens.trim().toIntOrNull() == null -> "Max tokens must be a whole number"
                newRequestTimeoutSeconds.trim().toIntOrNull() == null -> "Request timeout must be a whole number"
                else ->
                    // Reuses CompanionSettings.validationError() for the provider fields it shares
                    // with LlmProfile, by checking them against a throwaway settings copy.
                    CompanionSettings(
                        providerType = newProfileDraft.providerType,
                        model = newProfileDraft.model,
                        baseUrl = newProfileDraft.baseUrl,
                        contextWindowTokens = newProfileDraft.contextWindowTokens,
                        maxTokens = newProfileDraft.maxTokens,
                        requestTimeoutSeconds = newProfileDraft.requestTimeoutSeconds
                    ).validationError()
            }

            Column(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Profile name") },
                    modifier = Modifier.fillMaxWidth()
                )
                ProviderFieldsForm(
                    providerType = newProviderType,
                    onProviderTypeChange = { type ->
                        newProviderType = type
                        val defaults = defaultsForProvider(type)
                        newModel = defaults.model
                        newBaseUrl = defaults.baseUrl
                        newContextWindowTokens = defaults.contextWindowTokens
                    },
                    model = newModel,
                    onModelChange = { newModel = it },
                    baseUrl = newBaseUrl,
                    onBaseUrlChange = { newBaseUrl = it },
                    apiKey = newApiKey,
                    onApiKeyChange = { newApiKey = it },
                    contextWindowTokens = newContextWindowTokens,
                    onContextWindowTokensChange = { newContextWindowTokens = it },
                    maxTokens = newMaxTokens,
                    onMaxTokensChange = { newMaxTokens = it },
                    requestTimeoutSeconds = newRequestTimeoutSeconds,
                    onRequestTimeoutSecondsChange = { newRequestTimeoutSeconds = it }
                )
                if (newProfileError != null) {
                    Text(newProfileError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = newProfileError == null,
                        onClick = {
                            onSettingsChanged(settings.copy(profiles = settings.profiles + newProfileDraft))
                            addingProfile = false
                        }
                    ) { Text("Add profile") }
                    TextButton(onClick = { addingProfile = false }) { Text("Cancel") }
                }
            }
        } else {
            TextButton(modifier = Modifier.padding(top = 8.dp), onClick = { addingProfile = true }) { Text("+ New profile") }
        }

        Text("Voice mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))

        VoiceToggleRow(
            label = "Speech-to-text",
            checked = settings.sttEnabled,
            enabled = !installBusy,
            installed = isInstalled,
            onCheckedChange = { checked ->
                if (checked) enable { it.copy(sttEnabled = true) }
                else onSettingsChanged(settings.copy(sttEnabled = false))
            }
        )
        VoiceToggleRow(
            label = "Text-to-speech",
            checked = settings.ttsEnabled,
            enabled = !installBusy,
            installed = isInstalled,
            onCheckedChange = { checked ->
                if (checked) enable { it.copy(ttsEnabled = true) }
                else onSettingsChanged(settings.copy(ttsEnabled = false))
            }
        )

        when (val s = installState) {
            is InstallState.Downloading -> {
                Text("Downloading ${s.artifact}…", style = MaterialTheme.typography.bodySmall)
                if (s.bytesTotal > 0) {
                    LinearProgressIndicator(
                        progress = { s.bytesDone.toFloat() / s.bytesTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            InstallState.CheckingExisting -> Text("Checking existing install…", style = MaterialTheme.typography.bodySmall)
            InstallState.Verifying -> Text("Verifying downloads…", style = MaterialTheme.typography.bodySmall)
            InstallState.Extracting -> Text("Installing…", style = MaterialTheme.typography.bodySmall)
            is InstallState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { voiceInstaller.install() }) { Text("Retry") }
            }
            InstallState.Ready, InstallState.Idle -> Unit
        }

        Text("Memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Jane's Theory long-term memory (experimental) — recalls facts and lessons across sessions. " +
                "Requires an embedding model, separate from the chat model above.",
            style = MaterialTheme.typography.bodySmall
        )

        var memoryEnabled by remember(settings) { mutableStateOf(settings.memoryEnabled) }
        var embeddingModel by remember(settings) { mutableStateOf(settings.embeddingModel ?: "") }
        var embeddingBaseUrl by remember(settings) { mutableStateOf(settings.embeddingBaseUrl ?: "") }
        var embeddingApiKey by remember(settings) { mutableStateOf(settings.embeddingApiKey ?: "") }
        var embeddingDimensions by remember(settings) { mutableStateOf(settings.embeddingDimensions.toString()) }

        val memoryDraft = settings.copy(
            memoryEnabled = memoryEnabled,
            embeddingModel = embeddingModel.trim().ifBlank { null },
            embeddingBaseUrl = embeddingBaseUrl.trim().ifBlank { null },
            embeddingApiKey = embeddingApiKey.ifBlank { null },
            embeddingDimensions = embeddingDimensions.trim().toIntOrNull() ?: 0
        )
        val memoryError = when {
            embeddingDimensions.trim().toIntOrNull() == null -> "Embedding dimensions must be a whole number"
            else -> memoryDraft.validationError()
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Switch(checked = memoryEnabled, onCheckedChange = { memoryEnabled = it })
            Text(
                if (memoryEnabled) "Enabled" else "Disabled",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (memoryEnabled) {
            OutlinedTextField(
                value = embeddingModel,
                onValueChange = { embeddingModel = it },
                label = { Text("Embedding model") },
                placeholder = { Text("nomic-embed-text (Ollama) or text-embedding-3-small (OpenAI)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = embeddingBaseUrl,
                onValueChange = { embeddingBaseUrl = it },
                label = { Text("Embedding base URL") },
                placeholder = { Text(OLLAMA_BASE_URL) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = embeddingApiKey,
                onValueChange = { embeddingApiKey = it },
                label = { Text("Embedding API key (optional — blank is fine for a local Ollama/vLLM server)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = embeddingDimensions,
                onValueChange = { embeddingDimensions = it },
                label = { Text("Embedding dimensions") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (memoryError != null) {
            Text(memoryError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            modifier = Modifier.padding(top = 8.dp),
            enabled = memoryError == null,
            onClick = { onSettingsChanged(memoryDraft) }
        ) { Text("Apply") }

        Text("Workspace", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = settings.workspaceDir,
            onValueChange = { onSettingsChanged(settings.copy(workspaceDir = it)) },
            label = { Text("Workspace directory") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Root directory sophi-companion's file/bash tools are confined to. Scheduled and " +
                "goal-mode runs can fire unattended, so this stays sandboxed by default — point it " +
                "at a real projects folder for CLI-equivalent reach.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun VoiceToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    installed: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            "$label — " + when {
                checked -> "Enabled"
                installed -> "Installed, not enabled"
                else -> "Not installed"
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
