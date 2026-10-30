package dev.sophi.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What a host's desktop can do for the desktop tools; a `can*` false leaves that tool unregistered. */
interface Desktop {
    val canNotify: Boolean
    val canClipboard: Boolean
    val canOpen: Boolean
    suspend fun notify(title: String, message: String)
    suspend fun clipboardRead(): String
    suspend fun clipboardWrite(text: String)
    suspend fun open(target: String)
}

/**
 * The machine's own desktop through its commands: osascript/pbpaste/pbcopy/open on macOS, notify-send/xclip/xdg-open
 * on Linux when installed. [run] takes a command and its stdin and returns the exit code and stdout.
 */
class SystemDesktop(private val os: String, private val run: suspend (List<String>, String?) -> Pair<Int, String>) : Desktop {
    private val mac = os.startsWith("Mac")
    private fun onPath(cmd: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, cmd).canExecute() }
    private val notifySend = !mac && onPath("notify-send")

    override val canNotify = true // falls back to the terminal bell
    override val canClipboard = mac || onPath("xclip")
    override val canOpen = mac || onPath("xdg-open")

    private fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private suspend fun check(cmd: List<String>, stdin: String? = null): String {
        val (code, out) = run(cmd, stdin)
        if (code != 0) throw IllegalStateException("${cmd.first()} exited with code $code")
        return out
    }

    override suspend fun notify(title: String, message: String) {
        when {
            mac -> check(listOf("osascript", "-e", "display notification ${quoted(message)} with title ${quoted(title)}"))
            notifySend -> check(listOf("notify-send", title, message))
            else -> { System.err.print("\u0007"); System.err.flush() }
        }
    }

    override suspend fun clipboardRead(): String = check(if (mac) listOf("pbpaste") else listOf("xclip", "-selection", "clipboard", "-o"))

    override suspend fun clipboardWrite(text: String) {
        check(if (mac) listOf("pbcopy") else listOf("xclip", "-selection", "clipboard"), text)
    }

    override suspend fun open(target: String) {
        check(listOf(if (mac) "open" else "xdg-open", target))
    }

    companion object {
        fun detect(): SystemDesktop = SystemDesktop(System.getProperty("os.name").orEmpty(), ::runCommand)

        private suspend fun runCommand(cmd: List<String>, stdin: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            p.outputStream.use { o -> stdin?.let { o.write(it.toByteArray()) } }
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return@withContext -1 to out }
            p.exitValue() to out
        }
    }
}

private const val MAX_CLIPBOARD = 16_000
private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
// macOS and Linux launch these when opened: scripts, installers, app bundles
private val LAUNCHERS = setOf("app", "command", "tool", "terminal", "workflow", "pkg", "mpkg", "dmg", "scpt", "applescript", "jar", "sh", "desktop", "appimage")

private fun str(argumentsJson: String, key: String): String? =
    runCatching { (Json.parseToJsonElement(argumentsJson) as JsonObject)[key] as? JsonPrimitive }.getOrNull()?.contentOrNull

private class DesktopTool(
    override val name: String,
    override val description: String,
    override val parametersJson: String,
    private val risk: RiskLevel,
    private val preview: ((String) -> String?)? = null,
    private val body: suspend (String) -> String,
) : Tool {
    override fun riskLevel(argumentsJson: String) = risk
    override fun confirmationPreview(argumentsJson: String) = preview?.invoke(argumentsJson)
    override suspend fun execute(argumentsJson: String): String =
        runCatching { body(argumentsJson) }.getOrElse { "Error: ${it.message}" }
}

/** `notify`, `clipboard_read`, `clipboard_write` and `open`, for whatever [desktop] can do; `open` resolves paths against [root]. */
fun desktopTools(desktop: Desktop, root: Path): List<Tool> = buildList {
    if (desktop.canNotify) add(DesktopTool(
        "notify", "Show the user a desktop notification, e.g. when a long task or background job finishes and they may be away.",
        """{"type":"object","properties":{"title":{"type":"string"},"message":{"type":"string"}},"required":["title","message"]}""",
        RiskLevel.SAFE,
    ) { a -> desktop.notify(str(a, "title").orEmpty(), str(a, "message").orEmpty()); "Notified." })
    if (desktop.canClipboard) {
        add(DesktopTool(
            "clipboard_read", "Read the text on the user's clipboard (e.g. an error they just copied).",
            """{"type":"object","properties":{}}""", RiskLevel.CAUTION,
        ) { _ -> desktop.clipboardRead().let { if (it.length > MAX_CLIPBOARD) it.take(MAX_CLIPBOARD) + "\n… (truncated)" else it } })
        add(DesktopTool(
            "clipboard_write", "Put text on the user's clipboard so they can paste it.",
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""", RiskLevel.CAUTION,
            { a -> str(a, "text")?.let { "Copy to clipboard: " + it.take(200) } },
        ) { a -> val text = str(a, "text") ?: return@DesktopTool "Error: \"text\" is required"; desktop.clipboardWrite(text); "Copied ${text.length} chars." })
    }
    if (desktop.canOpen) add(DesktopTool(
        "open", "Open an http(s) URL in the user's browser, or an existing file in its default app (e.g. a report you wrote). It won't run programs.",
        """{"type":"object","properties":{"target":{"type":"string","description":"An http(s) URL, or a file path relative to the working directory"}},"required":["target"]}""",
        RiskLevel.CAUTION, { a -> str(a, "target")?.let { "Open $it" } },
    ) { a ->
        val target = str(a, "target")?.trim().orEmpty()
        val web = target.startsWith("http://") || target.startsWith("https://")
        val resolved = if (web) target else {
            if (SCHEME.containsMatchIn(target)) return@DesktopTool "Error: open only takes http(s) URLs or existing files"
            val home = System.getProperty("user.home")
            val p = (if (target.startsWith("~/")) Path.of(home, target.substring(2)) else root.resolve(target)).normalize()
            if (!Files.exists(p)) return@DesktopTool "Error: open only takes http(s) URLs or existing files"
            if (Files.isDirectory(p) || Files.isExecutable(p) || p.fileName.toString().substringAfterLast('.', "").lowercase() in LAUNCHERS)
                return@DesktopTool "Error: open won't launch programs"
            p.toString()
        }
        desktop.open(resolved)
        "Opened $target."
    })
}
