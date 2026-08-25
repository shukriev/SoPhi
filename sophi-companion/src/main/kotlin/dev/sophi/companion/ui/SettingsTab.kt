package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

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
