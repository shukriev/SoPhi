package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import dev.sophi.mcp.config.McpConfigLoader
import dev.sophi.sdk.DoctorCheck
import dev.sophi.sdk.checkCommand
import dev.sophi.sdk.checkMcpServers
import dev.sophi.sdk.checkSkillPins
import dev.sophi.sdk.mcpToolCounter
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/**
 * `sophi doctor`: settings and model first (what every start needs), then [others] given the usable settings.
 * Prints a line per check and a `fix:` line per failure; [run] returns the exit code.
 */
class Doctor(
    private val settingsFile: Path,
    private val resolve: (type: String, baseUrl: String?, model: String?, contextWindowTokens: Int?) -> ResolvedProvider,
    private val others: (settings: Map<String, String>) -> List<DoctorCheck>,
    private val echo: (String) -> Unit,
) {
    fun run(): Int {
        val warnings = mutableListOf<String>()
        val store = CliSettingsStore(settingsFile) { warnings += it }
        val settings = store.usable()
        val settingsChecks = when {
            warnings.isNotEmpty() -> {
                val fix = if (store.cannotSave() != null) "edit or delete $settingsFile" else "run /settings"
                warnings.map { DoctorCheck("settings", false, it, fix) }
            }
            !settingsFile.exists() -> listOf(DoctorCheck("settings", true, "no settings saved; defaults"))
            else -> listOf(DoctorCheck("settings", true, "$settingsFile: ${settings.size} saved"))
        }
        val checks = settingsChecks + model(settings) + others(settings)
        for (c in checks) {
            echo("${if (c.ok) "ok     " else "problem"}  ${c.name}: ${c.detail}")
            c.fix?.let { echo("         fix: $it") }
        }
        return if (checks.all { it.ok }) 0 else 1
    }

    private fun model(settings: Map<String, String>): DoctorCheck {
        val type = settings["provider"] ?: "local"
        return try {
            val r = resolve(type, settings["base-url"], settings["model"], settings["context-window-tokens"]?.toIntOrNull())
            val notes = r.warnings.joinToString("") { "; note: $it" }
            DoctorCheck("model", true, "${r.type} at ${r.baseUrl ?: "its default URL"}, ${r.model}, ${r.contextWindowTokens}-token window$notes")
        } catch (e: UsageError) {
            val message = e.message ?: e.toString()
            val fix = when {
                "no model loaded" in message -> "load a model or set /settings model"
                type == "local" -> "sophi setup local"
                else -> "set provider, base-url and model in /settings"
            }
            DoctorCheck("model", false, message, fix)
        }
    }
}

class DoctorCommand : CliktCommand(name = "doctor", help = "Check that Sophi can start: model server, settings, MCP servers, skills") {
    override fun run() {
        val home = Path.of(System.getProperty("user.home"))
        val code = Doctor(
            settingsFile = DEFAULT_SETTINGS_FILE,
            resolve = { t, url, m, w -> resolveProvider(t, url, m, w, loadModelCatalog()) },
            others = { settings ->
                // Same default as SophiCli's --mcp-config.
                val mcpPath = Path.of(settings["mcp-config"] ?: ".sophi/mcp.json")
                val mcp = runCatching { McpConfigLoader().load(mcpPath) }
                buildList {
                    mcp.onSuccess { addAll(runBlocking { checkMcpServers(it.servers, mcpPath, mcpToolCounter(), 15.seconds) }) }
                        .onFailure { add(DoctorCheck("mcp", false, "$mcpPath doesn't parse: ${it.message?.lineSequence()?.first()}", "fix or delete $mcpPath")) }
                    addAll(checkSkillPins(home.resolve(".sophi/skills"), Path.of(".sophi", "skills")))
                    if (settings["sandbox"] == "docker") add(checkCommand("docker", listOf("docker", "info"), 10.seconds, required = true,
                        hint = "start Docker Desktop or /settings sandbox seatbelt"))
                    add(checkCommand("claude", listOf("claude", "--version"), 10.seconds, required = false,
                        hint = "invoke_claude_code needs the claude CLI"))
                }
            },
            echo = { echo(it) },
        ).run()
        if (code != 0) throw ProgramResult(code)
    }
}
