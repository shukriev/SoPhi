package dev.sophi.companion

/**
 * Pops a native macOS text-entry dialog and returns what the user typed. A true inline-reply
 * banner notification isn't practical from a JVM/Compose app (it needs macOS's UserNotifications
 * framework, a signed app bundle, and registered notification categories) — a modal
 * `display dialog` via osascript gets the same "something pops up, you type into it" result using
 * the same shell-out mechanism [dev.sophi.schedule.notify.NativeNotifications] already uses for
 * plain banners.
 */
object NativeDialogs {
    data class DialogResult(val exitCode: Int, val stdout: String)

    /**
     * Returns the typed text, or null if the user hit Cancel, left it blank, this isn't macOS, or
     * [runCommand] failed. [runCommand] is a test seam — the default actually shells out.
     */
    fun promptText(
        title: String,
        message: String,
        os: String = System.getProperty("os.name"),
        runCommand: (List<String>) -> DialogResult = ::runOsascript
    ): String? {
        if (!os.lowercase().contains("mac")) return null
        val result = runCatching { runCommand(listOf("osascript", "-e", dialogScript(title, message))) }
            .getOrNull() ?: return null
        if (result.exitCode != 0) return null
        return Regex("text returned:(.*)$").find(result.stdout)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun runOsascript(cmd: List<String>): DialogResult {
        val process = ProcessBuilder(cmd).start()
        val stdout = process.inputStream.bufferedReader().readText()
        return DialogResult(process.waitFor(), stdout)
    }

    private fun dialogScript(title: String, message: String): String =
        "display dialog ${quote(message)} with title ${quote(title)} default answer \"\" " +
            "buttons {\"Cancel\", \"OK\"} default button \"OK\""

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
