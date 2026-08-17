package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.skills.InstallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSkillDialog(onInstall: (String) -> InstallResult, onDismiss: () -> Unit) {
    var source by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<InstallResult?>(null) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp).width(400.dp)) {
                Text("Add skill", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = source, onValueChange = { source = it },
                    label = { Text("Source") },
                    placeholder = { Text("~/my-skill or https://github.com/user/repo") },
                    modifier = Modifier.fillMaxWidth()
                )
                result?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            if (r.installed.isNotEmpty()) append("Installed: ${r.installed.joinToString(", ")}. ")
                            if (r.skipped.isNotEmpty()) append("Already present: ${r.skipped.joinToString(", ")}. ")
                            if (r.installed.isEmpty() && r.skipped.isEmpty()) append("No skills found at that source.")
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = source.isNotBlank(),
                        onClick = { scope.launch { result = onInstall(source.trim()) } }
                    ) { Text("Install") }
                }
            }
        }
    }
}
