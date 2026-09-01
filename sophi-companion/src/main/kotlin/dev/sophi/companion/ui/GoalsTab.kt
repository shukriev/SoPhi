package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class TriggerKind { Manual, Interval, Cron, Once }
private enum class ModeKind { Recurring, Goal }
private enum class StopConditionKind { LlmJudged, ShellCheck }

@Composable
fun GoalsTab(runtime: CompanionRuntime) {
    var tasks by remember { mutableStateOf(listOf<ScheduledTask>()) }
    var detailTask by remember { mutableStateOf<ScheduledTask?>(null) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    fun refresh() { tasks = runtime.tasks() }
    LaunchedEffect(Unit) { refresh() }

    detailTask?.let { task ->
        TaskDetail(
            task = task,
            history = runtime.runHistory(task.id),
            onPause = { runtime.pauseTask(task.id); refresh(); detailTask = tasks.find { it.id == task.id } },
            onResume = { runtime.resumeTask(task.id); refresh(); detailTask = tasks.find { it.id == task.id } },
            onRemove = { runtime.removeTask(task.id); refresh(); detailTask = null },
            onBack = { detailTask = null }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("Goals / Tasks", style = MaterialTheme.typography.titleMedium)
        CreateTaskForm(onCreate = { name, prompt, trigger, mode ->
            runtime.createTask(name, prompt, mode, trigger)
            refresh()
        })

        LazyColumn {
            items(tasks, key = { it.id }) { task ->
                ListItem(
                    headlineContent = { Text(task.name) },
                    supportingContent = {
                        Text("${if (task.enabled) "enabled" else "paused"} · last run: ${task.lastRunAtMs ?: "never"}")
                    },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { scope.launch { runtime.runTaskNow(task.id); refresh() } }) { Text("Run now") }
                            TextButton(onClick = { detailTask = task }) { Text("Details") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun TaskDetail(
    task: ScheduledTask,
    history: List<RunRecord>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(task.name, style = MaterialTheme.typography.titleMedium)
        Text(task.prompt, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        Row {
            if (task.enabled) TextButton(onClick = onPause) { Text("Pause") }
            else TextButton(onClick = onResume) { Text("Resume") }
            TextButton(onClick = onRemove) { Text("Remove") }
        }

        Text("Run history", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        if (history.isEmpty()) Text("No runs yet.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn {
            items(history) { run ->
                ListItem(
                    headlineContent = { Text(run.outcome::class.simpleName ?: "unknown") },
                    supportingContent = { Text(run.summary) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CreateTaskForm(onCreate: (name: String, prompt: String, trigger: Trigger, mode: TaskMode) -> Unit) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var triggerKind by remember { mutableStateOf(TriggerKind.Manual) }
    var intervalSeconds by remember { mutableStateOf("") }
    var cronExpression by remember { mutableStateOf("") }
    var onceAtMs by remember { mutableStateOf("") }
    var modeKind by remember { mutableStateOf(ModeKind.Recurring) }
    var maxIterations by remember { mutableStateOf("10") }
    var stopConditionKind by remember { mutableStateOf(StopConditionKind.LlmJudged) }
    var shellCheckCommand by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Task name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth())

        Text("Trigger", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        Row {
            TriggerKind.entries.forEach { kind ->
                FilterChip(
                    selected = triggerKind == kind, onClick = { triggerKind = kind },
                    label = { Text(kind.name) }, modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        when (triggerKind) {
            TriggerKind.Interval -> OutlinedTextField(
                value = intervalSeconds, onValueChange = { intervalSeconds = it },
                label = { Text("Every N seconds") }, modifier = Modifier.fillMaxWidth()
            )
            TriggerKind.Cron -> OutlinedTextField(
                value = cronExpression, onValueChange = { cronExpression = it },
                label = { Text("Cron expression") }, modifier = Modifier.fillMaxWidth()
            )
            TriggerKind.Once -> OutlinedTextField(
                value = onceAtMs, onValueChange = { onceAtMs = it },
                label = { Text("Run at (epoch ms)") }, modifier = Modifier.fillMaxWidth()
            )
            TriggerKind.Manual -> Unit
        }

        Text("Mode", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        Row {
            ModeKind.entries.forEach { kind ->
                FilterChip(
                    selected = modeKind == kind, onClick = { modeKind = kind },
                    label = { Text(kind.name) }, modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        if (modeKind == ModeKind.Goal) {
            OutlinedTextField(
                value = maxIterations, onValueChange = { maxIterations = it },
                label = { Text("Max iterations") }, modifier = Modifier.fillMaxWidth()
            )
            Row {
                StopConditionKind.entries.forEach { kind ->
                    FilterChip(
                        selected = stopConditionKind == kind, onClick = { stopConditionKind = kind },
                        label = { Text(kind.name) }, modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
            if (stopConditionKind == StopConditionKind.ShellCheck) {
                OutlinedTextField(
                    value = shellCheckCommand, onValueChange = { shellCheckCommand = it },
                    label = { Text("Shell command (exit 0 = stop)") }, modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Button(onClick = {
            val trigger = when (triggerKind) {
                TriggerKind.Manual -> Trigger.Manual
                TriggerKind.Interval -> Trigger.Interval(intervalSeconds.toLongOrNull() ?: 60L)
                TriggerKind.Cron -> Trigger.Cron(cronExpression)
                TriggerKind.Once -> Trigger.Once(onceAtMs.toLongOrNull() ?: System.currentTimeMillis())
            }
            val mode = when (modeKind) {
                ModeKind.Recurring -> TaskMode.Recurring
                ModeKind.Goal -> TaskMode.Goal(
                    stopCondition = when (stopConditionKind) {
                        StopConditionKind.LlmJudged -> StopCondition.LlmJudged
                        StopConditionKind.ShellCheck -> StopCondition.ShellCheck(shellCheckCommand)
                    },
                    maxIterations = maxIterations.toIntOrNull() ?: 10
                )
            }
            onCreate(name, prompt, trigger, mode)
            name = ""; prompt = ""
        }) { Text("Create task") }
    }
}
