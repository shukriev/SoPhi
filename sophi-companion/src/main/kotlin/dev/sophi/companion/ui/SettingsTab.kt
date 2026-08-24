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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionSettings
import dev.sophi.companion.voice.InstallState
import dev.sophi.companion.voice.VoiceInstaller

@Composable
fun SettingsTab(
    settings: CompanionSettings,
    onSettingsChanged: (CompanionSettings) -> Unit,
    voiceInstaller: VoiceInstaller
) {
    val installState by voiceInstaller.state.collectAsState()
    var isInstalled by remember { mutableStateOf(false) }

    // isInstalled() is the read-only, network-free check — used once on entry so the tab shows
    // "Installed"/"Not installed" correctly without ever calling install() just to find out.
    LaunchedEffect(Unit) { isInstalled = voiceInstaller.isInstalled() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("Voice mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Switch(
                checked = settings.voiceEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        voiceInstaller.install()
                    } else {
                        onSettingsChanged(settings.copy(voiceEnabled = false))
                    }
                },
                enabled = installState !is InstallState.Downloading &&
                    installState !is InstallState.Verifying &&
                    installState !is InstallState.Extracting &&
                    installState !is InstallState.CheckingExisting
            )
            Text(
                when {
                    settings.voiceEnabled -> "Enabled"
                    isInstalled -> "Installed, not enabled"
                    else -> "Not installed"
                },
                modifier = Modifier.padding(start = 8.dp)
            )
        }

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
            InstallState.Ready -> {
                // install() reached Ready — this is the only place voiceEnabled actually
                // flips to true, keeping settings.voiceEnabled (not InstallState) as the
                // toggle's single source of truth.
                LaunchedEffect(Unit) {
                    if (!settings.voiceEnabled) onSettingsChanged(settings.copy(voiceEnabled = true))
                }
            }
            is InstallState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { voiceInstaller.install() }) { Text("Retry") }
            }
            InstallState.Idle -> Unit
        }
    }
}
