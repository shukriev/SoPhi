package dev.sophi.schedule.notify

import java.awt.SystemTray
import java.awt.TrayIcon

object NativeNotifications {
    fun send(
        title: String,
        body: String,
        os: String = System.getProperty("os.name"),
        runCommand: (List<String>) -> Int = { cmd -> ProcessBuilder(cmd).start().waitFor() },
        windowsNotify: (String, String) -> Unit = ::displayAwtTrayMessage
    ) {
        val normalized = os.lowercase()
        runCatching {
            when {
                normalized.contains("mac") -> runCommand(listOf("osascript", "-e", appleScript(title, body)))
                normalized.contains("linux") -> runCommand(listOf("notify-send", title, body))
                normalized.contains("win") -> windowsNotify(title, body)
                else -> Unit
            }
        }
    }

    private fun displayAwtTrayMessage(title: String, body: String) {
        if (!SystemTray.isSupported()) return
        SystemTray.getSystemTray().trayIcons.firstOrNull()
            ?.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }

    private fun appleScript(title: String, body: String): String =
        "display notification ${quote(body)} with title ${quote(title)}"

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
