package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.sophi.companion.activeProfile
import dev.sophi.companion.validationError
import dev.sophi.companion.voice.InstallState
import dev.sophi.companion.voice.VoiceInstaller
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    settings: CompanionSettings,
    onSettingsChanged: (CompanionSettings) -> Unit,
    voiceInstaller: VoiceInstaller
) {
    val installState by voiceInstaller.state.collectAsState()
    var isInstalled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { isInstalled = voiceInstaller.isInstalled() }

    val installBusy = installState is InstallState.Downloading ||
        installState is InstallState.Verifying ||
        installState is InstallState.Extracting ||
        installState is InstallState.CheckingExisting

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

        Text("Profile", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "A profile is one full connection — LLM provider and memory config together. " +
                "Pick one from the dropdown to view/edit it, or create a new one.",
            style = MaterialTheme.typography.bodySmall
        )

        var selectedProfileName by remember(settings) { mutableStateOf(settings.activeProfileName) }
        var isNewProfile by remember(settings) { mutableStateOf(false) }
        var pendingSelection by remember { mutableStateOf<String?>(null) }
        var pendingIsNew by remember { mutableStateOf(false) }
        var showDiscardDialog by remember { mutableStateOf(false) }

        val newDefaults = remember { defaultsForProvider(ProviderTypes.CLAUDE) }
        val selectedProfile = settings.profiles.find { it.name == selectedProfileName } ?: settings.activeProfile()

        fun blankNewProfile() = LlmProfile(
            name = "", providerType = ProviderTypes.CLAUDE, model = newDefaults.model,
            baseUrl = newDefaults.baseUrl.ifBlank { null },
            contextWindowTokens = newDefaults.contextWindowTokens.toIntOrNull() ?: 200_000
        )

        // The snapshot to diff drafts against for dirty-tracking — re-captured only when
        // (selectedProfileName, isNewProfile) changes (a different profile picked, or
        // entering/leaving "new" mode; remember's two-key overload avoids needing a combined
        // sentinel key that could theoretically collide with a real profile name), and explicitly
        // reassigned after a successful Save (see below) so Save immediately clears the dirty flag
        // instead of it staying stuck true until the next selection change.
        var savedBaseline by remember(selectedProfileName, isNewProfile) {
            mutableStateOf(if (isNewProfile) blankNewProfile() else selectedProfile)
        }

        var name by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.name) }
        var providerType by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.providerType) }
        var model by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.model) }
        var baseUrl by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.baseUrl ?: "") }
        var apiKey by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.apiKey ?: "") }
        var contextWindowTokens by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.contextWindowTokens.toString()) }
        var maxTokens by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.maxTokens.toString()) }
        var requestTimeoutSeconds by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.requestTimeoutSeconds.toString()) }
        var memoryEnabled by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.memoryEnabled) }
        var embeddingModel by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.embeddingModel ?: "") }
        var embeddingBaseUrl by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.embeddingBaseUrl ?: "") }
        var embeddingApiKey by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.embeddingApiKey ?: "") }
        var embeddingDimensions by remember(selectedProfileName, isNewProfile) { mutableStateOf(savedBaseline.embeddingDimensions.toString()) }

        val draftProfile = LlmProfile(
            name = name.trim(),
            providerType = providerType,
            model = model.trim(),
            baseUrl = baseUrl.trim().ifBlank { null },
            apiKey = apiKey.ifBlank { null },
            contextWindowTokens = contextWindowTokens.trim().toIntOrNull() ?: 0,
            maxTokens = maxTokens.trim().toIntOrNull() ?: 0,
            requestTimeoutSeconds = requestTimeoutSeconds.trim().toIntOrNull() ?: 0,
            memoryEnabled = memoryEnabled,
            embeddingModel = embeddingModel.trim().ifBlank { null },
            embeddingBaseUrl = embeddingBaseUrl.trim().ifBlank { null },
            embeddingApiKey = embeddingApiKey.ifBlank { null },
            embeddingDimensions = embeddingDimensions.trim().toIntOrNull() ?: 0
        )
        val isDirty = draftProfile != savedBaseline

        fun applySelection(profileName: String?, isNew: Boolean) {
            if (!isNew) selectedProfileName = profileName!!
            isNewProfile = isNew
        }

        fun requestSelect(profileName: String?, isNew: Boolean) {
            if (isDirty) {
                pendingSelection = profileName
                pendingIsNew = isNew
                showDiscardDialog = true
            } else {
                applySelection(profileName, isNew)
            }
        }

        if (showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                title = { Text("Discard unsaved changes?") },
                text = { Text("You have unsaved changes to '${if (isNewProfile) "new profile" else selectedProfileName}'.") },
                confirmButton = {
                    TextButton(onClick = {
                        applySelection(pendingSelection, pendingIsNew)
                        showDiscardDialog = false
                    }) { Text("Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardDialog = false }) { Text("Cancel") }
                }
            )
        }

        var providerMenuExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = providerMenuExpanded,
            onExpandedChange = { providerMenuExpanded = it },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            OutlinedTextField(
                value = if (isNewProfile) "New profile…" else selectedProfileName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Profile") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }) {
                settings.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name + if (profile.name == settings.activeProfileName) " (active)" else "") },
                        onClick = {
                            providerMenuExpanded = false
                            requestSelect(profile.name, isNew = false)
                        }
                    )
                }
            }
        }

        Text("Connection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        if (isNewProfile) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        ProviderFieldsForm(
            providerType = providerType,
            onProviderTypeChange = { type ->
                providerType = type
                val defaults = defaultsForProvider(type)
                model = defaults.model
                baseUrl = defaults.baseUrl
                contextWindowTokens = defaults.contextWindowTokens
            },
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

        Text("Memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Jane's Theory long-term memory (experimental) — recalls facts and lessons across sessions.",
            style = MaterialTheme.typography.bodySmall
        )
        MemoryFieldsForm(
            memoryEnabled = memoryEnabled,
            onMemoryEnabledChange = { memoryEnabled = it },
            embeddingModel = embeddingModel,
            onEmbeddingModelChange = { embeddingModel = it },
            embeddingBaseUrl = embeddingBaseUrl,
            onEmbeddingBaseUrlChange = { embeddingBaseUrl = it },
            embeddingApiKey = embeddingApiKey,
            onEmbeddingApiKeyChange = { embeddingApiKey = it },
            embeddingDimensions = embeddingDimensions,
            onEmbeddingDimensionsChange = { embeddingDimensions = it }
        )

        val nameCollision = isNewProfile && settings.profiles.any { it.name == draftProfile.name }
        val saveError = when {
            draftProfile.name.isBlank() -> "Profile name must not be blank"
            nameCollision -> "A profile named '${draftProfile.name}' already exists"
            contextWindowTokens.trim().toIntOrNull() == null -> "Context window must be a whole number"
            maxTokens.trim().toIntOrNull() == null -> "Max tokens must be a whole number"
            requestTimeoutSeconds.trim().toIntOrNull() == null -> "Request timeout must be a whole number"
            embeddingDimensions.trim().toIntOrNull() == null -> "Embedding dimensions must be a whole number"
            else -> CompanionSettings(profiles = listOf(draftProfile), activeProfileName = draftProfile.name).validationError()
        }
        if (saveError != null) {
            Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        val isActive = !isNewProfile && selectedProfileName == settings.activeProfileName
        val canDelete = !isNewProfile && !isActive && settings.profiles.size > 1

        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = saveError == null,
                onClick = {
                    val updatedProfiles = if (isNewProfile) settings.profiles + draftProfile
                        else settings.profiles.map { if (it.name == selectedProfileName) draftProfile else it }
                    onSettingsChanged(settings.copy(profiles = updatedProfiles))
                    selectedProfileName = draftProfile.name
                    isNewProfile = false
                    savedBaseline = draftProfile
                }
            ) { Text("Save") }

            Button(
                enabled = !isDirty && !isNewProfile && !isActive,
                onClick = { onSettingsChanged(settings.copy(activeProfileName = selectedProfileName)) }
            ) { Text(if (isActive) "Active" else "Set Active") }

            TextButton(onClick = { requestSelect(null, isNew = true) }) { Text("+ New") }

            TextButton(
                enabled = canDelete,
                onClick = {
                    onSettingsChanged(settings.copy(profiles = settings.profiles.filterNot { it.name == selectedProfileName }))
                    selectedProfileName = settings.activeProfileName
                    isNewProfile = false
                }
            ) { Text("Delete") }
        }

        Text("Voice mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))

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
        VoiceToggleRow(
            label = "Ambient listening",
            checked = settings.ambientListeningEnabled,
            enabled = !installBusy,
            installed = isInstalled,
            onCheckedChange = { checked ->
                if (checked) enable { it.copy(ambientListeningEnabled = true) }
                else onSettingsChanged(settings.copy(ambientListeningEnabled = false))
            }
        )
        Text(
            "Passively listens while this window is open and stores notable ambient speech as " +
                "memory, including facts about people other than you. No consent prompt is shown to " +
                "anyone else present — that's on you to handle. Review what it captured in the Memory tab.",
            style = MaterialTheme.typography.bodySmall
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
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
