package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.skills.Skill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SkillsTab(runtime: CompanionRuntime) {
    var skills by remember { mutableStateOf(listOf<Pair<String, Skill>>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    fun refresh() { skills = runtime.skills() }
    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Skills", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { showAddDialog = true }) { Text("+ Add skill") }
        }
        LazyColumn {
            items(skills, key = { it.first }) { (id, skill) ->
                ListItem(
                    headlineContent = { Text(skill.metadata.title) },
                    supportingContent = { Text(skill.metadata.description) },
                    trailingContent = {
                        TextButton(onClick = { scope.launch { runtime.removeSkill(id); refresh() } }) { Text("Remove") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            onInstall = { source -> runtime.installSkill(source) },
            onDismiss = { showAddDialog = false; refresh() }
        )
    }
}
