package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
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
        Text("Goals / Tasks")
        OutlinedTextField(value = newTaskName, onValueChange = { newTaskName = it }, label = { Text("Task name") })
        OutlinedTextField(value = newTaskPrompt, onValueChange = { newTaskPrompt = it }, label = { Text("Prompt") })
        Button(onClick = {
            runtime.createTask(newTaskName, newTaskPrompt)
            newTaskName = ""
            newTaskPrompt = ""
            refresh()
        }) { Text("Create task") }

        LazyColumn {
            items(tasks) { task ->
                Row(modifier = Modifier.padding(4.dp)) {
                    Text("${task.name} — last run: ${task.lastRunAtMs ?: "never"}", modifier = Modifier.weight(1f))
                    Button(onClick = { scope.launch { runtime.runTaskNow(task.id); refresh() } }) { Text("Run now") }
                }
            }
        }
    }
}
