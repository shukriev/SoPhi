package dev.sophi.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState

fun main() = application {
    var isWindowVisible by remember { mutableStateOf(false) }
    val trayState = rememberTrayState()

    Tray(
        icon = painterResource("icons/logo.png"),
        state = trayState,
        tooltip = "Sophi Companion",
        onAction = { isWindowVisible = !isWindowVisible },
        menu = {
            Item("Open Sophi", onClick = { isWindowVisible = true })
            Item("Quit", onClick = ::exitApplication)
        }
    )

    if (isWindowVisible) {
        Window(onCloseRequest = { isWindowVisible = false }, title = "Sophi Companion") {
            MaterialTheme {
                Box(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
                    Text("Sophi Companion — coming together")
                }
            }
        }
    }
}
