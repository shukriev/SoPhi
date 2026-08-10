package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.schedule.model.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun GoalsTab(runtime: CompanionRuntime) {
    var tasks by remember { mutableStateOf(listOf<ScheduledTask>()) }
    var newTaskName by remember { mutableStateOf("") }
    var newTaskPrompt by remember { mutableStateOf("") }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    fun refresh() { tasks = runtime.tasks() }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("Goals / Tasks", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = newTaskName,
            onValueChange = { newTaskName = it },
            label = { Text("Task name") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = newTaskPrompt,
            onValueChange = { newTaskPrompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            runtime.createTask(newTaskName, newTaskPrompt)
            newTaskName = ""
            newTaskPrompt = ""
            refresh()
        }) { Text("Create task") }

        LazyColumn {
            items(tasks) { task ->
                ListItem(
                    headlineContent = { Text(task.name) },
                    supportingContent = { Text("Last run: ${task.lastRunAtMs ?: "never"}") },
                    trailingContent = {
                        TextButton(onClick = { scope.launch { runtime.runTaskNow(task.id); refresh() } }) { Text("Run now") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
