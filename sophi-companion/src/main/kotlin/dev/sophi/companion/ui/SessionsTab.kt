package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
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
import dev.sophi.core.session.SessionMeta
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@Composable
fun SessionsTab(runtime: CompanionRuntime, onOpen: (String) -> Unit) {
    var sessions by remember { mutableStateOf(listOf<SessionMeta>()) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    fun refresh() { sessions = runtime.sessionManager.list() }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Button(onClick = {
            scope.launch {
                val id = runtime.newSession()
                refresh()
                onOpen(id)
            }
        }) { Text("New session") }

        LazyColumn {
            items(sessions) { meta ->
                Row(modifier = Modifier.padding(4.dp)) {
                    Text(meta.title ?: meta.id, modifier = Modifier.weight(1f))
                    Button(onClick = { onOpen(meta.id) }) { Text("Open") }
                    Button(onClick = {
                        runtime.sessionManager.rename(meta.id, "Renamed session")
                        refresh()
                    }) { Text("Rename") }
                    Button(onClick = {
                        runtime.sessionManager.delete(meta.id)
                        refresh()
                    }) { Text("Delete") }
                }
            }
        }
    }
}
