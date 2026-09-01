package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.memory.BrowseFilter
import dev.sophi.memory.MemoryView
import dev.sophi.memory.ProfileAttributeView
import dev.sophi.memory.jane.ConsolidationRecord

private enum class MemorySection(val label: String) {
    Browse("Browse"), Lessons("Lessons"), Threads("Threads"), Profile("Profile & Consolidations")
}

private val ROOMS = listOf(null, "entities", "tasks", "episodes", "knowledge", "narrative")

@Composable
fun MemoryTab(runtime: CompanionRuntime) {
    var section by remember { mutableStateOf(MemorySection.Browse) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("Memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            MemorySection.entries.forEach { s ->
                FilterChip(
                    selected = section == s,
                    onClick = { section = s },
                    label = { Text(s.label) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        when (section) {
            MemorySection.Browse -> BrowseSection(runtime)
            MemorySection.Lessons -> LessonsSection(runtime)
            MemorySection.Threads -> ThreadsSection(runtime)
            MemorySection.Profile -> ProfileSection(runtime)
        }
    }
}

@Composable
private fun BrowseSection(runtime: CompanionRuntime) {
    var room by remember { mutableStateOf<String?>(null) }
    var memories by remember { mutableStateOf(listOf<MemoryView>()) }
    var selected by remember { mutableStateOf<MemoryView?>(null) }

    fun refresh() { memories = runtime.memoryBrowse(BrowseFilter(room = room)) }
    LaunchedEffect(room) { refresh() }

    if (selected != null) {
        val m = selected!!
        Column(modifier = Modifier.fillMaxSize()) {
            Text(m.id, style = MaterialTheme.typography.titleSmall)
            Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            m.metadata.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
            androidx.compose.material3.TextButton(onClick = { selected = null }) { Text("Back") }
        }
        return
    }

    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        ROOMS.forEach { r ->
            FilterChip(
                selected = room == r,
                onClick = { room = r },
                label = { Text(r ?: "all") },
                modifier = Modifier.padding(end = 6.dp)
            )
        }
    }
    if (memories.isEmpty()) Text("No memories.", style = MaterialTheme.typography.bodyMedium)
    LazyColumn {
        items(memories, key = { it.id }) { m ->
            ListItem(
                headlineContent = { Text(m.text) },
                supportingContent = { Text(m.id) },
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.TextButton(onClick = { selected = m }) { Text("View detail") }
        }
    }
}

@Composable
private fun LessonsSection(runtime: CompanionRuntime) {
    var lessons by remember { mutableStateOf(listOf<dev.sophi.learning.Lesson>()) }
    LaunchedEffect(Unit) { lessons = runtime.lessons() }

    if (lessons.isEmpty()) Text("No lessons.", style = MaterialTheme.typography.bodyMedium)
    LazyColumn {
        items(lessons, key = { it.id }) { lesson ->
            ListItem(
                headlineContent = { Text(lesson.text) },
                supportingContent = { Text("[${lesson.kind}] use=${lesson.useCount}") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ThreadsSection(runtime: CompanionRuntime) {
    var threads by remember { mutableStateOf(mapOf<String, List<String>>()) }
    LaunchedEffect(Unit) { threads = runtime.memoryThreads() }

    if (threads.isEmpty()) Text("No narrative threads.", style = MaterialTheme.typography.bodyMedium)
    LazyColumn {
        items(threads.entries.toList(), key = { it.key }) { (title, lines) ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ProfileSection(runtime: CompanionRuntime) {
    var profile by remember { mutableStateOf(listOf<ProfileAttributeView>()) }
    var history by remember { mutableStateOf(listOf<ConsolidationRecord>()) }
    LaunchedEffect(Unit) { profile = runtime.memoryProfile(); history = runtime.consolidationHistory() }

    Text("User profile", style = MaterialTheme.typography.titleSmall)
    if (profile.isEmpty()) Text("No profile attributes.", style = MaterialTheme.typography.bodyMedium)
    profile.forEach { attr ->
        ListItem(
            headlineContent = { Text(attr.path) },
            supportingContent = { Text("${attr.value}  (confidence=${attr.confidence})") },
            modifier = Modifier.fillMaxWidth()
        )
    }

    Text("Consolidation history", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    if (history.isEmpty()) Text("No consolidation runs recorded.", style = MaterialTheme.typography.bodyMedium)
    LazyColumn {
        items(history, key = { it.id }) { run ->
            ListItem(
                headlineContent = { Text(run.id) },
                supportingContent = {
                    Text("merged=${run.merged} strengthened=${run.strengthened} compressed=${run.compressed} pruned=${run.pruned}")
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
