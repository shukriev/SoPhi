package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.sophi.companion.CompanionRuntime

private enum class AppTab(val label: String) {
    CHAT("Chat"), SESSIONS("Sessions"), MCP("MCP"), GOALS("Goals")
}

@Composable
fun AppTabs(runtime: CompanionRuntime) {
    var selected by remember { mutableStateOf(AppTab.CHAT) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (activeSessionId == null) activeSessionId = runtime.newSession()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selected.ordinal) {
            AppTab.entries.forEach { tab ->
                Tab(
                    selected = selected == tab,
                    onClick = { selected = tab },
                    text = { Text(tab.label) }
                )
            }
        }
        val sessionId = activeSessionId
        when {
            sessionId == null -> Text("Starting…")
            selected == AppTab.CHAT -> ChatTab(runtime, sessionId)
            selected == AppTab.SESSIONS -> SessionsTab(runtime, onOpen = { activeSessionId = it })
            selected == AppTab.MCP -> McpTab(runtime)
            selected == AppTab.GOALS -> GoalsTab(runtime)
        }
    }
}
