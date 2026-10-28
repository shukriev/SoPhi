package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.OptionDelegate
import com.github.ajalt.clikt.sources.ExperimentalValueSourceApi
import com.github.ajalt.clikt.sources.MapValueSource
import dev.sophi.core.tools.BashSandbox
import dev.sophi.core.tools.SandboxMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

val DEFAULT_SETTINGS_FILE: Path = Path.of(System.getProperty("user.home"), ".sophi", "settings.json")

sealed interface SettingKind {
    object Bool : SettingKind
    data class Choice(val values: List<String>) : SettingKind
    object Int : SettingKind
    object Text : SettingKind
}

/** One `sophi` start flag (its long name without `--`); [live] ones change the running session, the rest apply next start. */
data class Setting(val key: String, val kind: SettingKind, val live: Boolean = false)

private fun live(key: String, kind: SettingKind) = Setting(key, kind, live = true)
private fun next(key: String, kind: SettingKind) = Setting(key, kind)

/** Every `sophi` start flag, in menu order: the ones that change the running session first. */
val CLI_SETTINGS: List<Setting> = listOf(
    live("sandbox", SettingKind.Choice(listOf("off", "seatbelt", "docker"))),
    live("network", SettingKind.Bool),
    live("docker-image", SettingKind.Text),
    live("auto", SettingKind.Bool),
    next("provider", SettingKind.Choice(listOf("local", "openai-compat", "claude"))),
    next("model", SettingKind.Text),
    next("base-url", SettingKind.Text),
    next("context-window-tokens", SettingKind.Int),
    next("max-tokens", SettingKind.Int),
    next("llm-timeout-seconds", SettingKind.Int),
    next("llm-max-retries", SettingKind.Int),
    next("system", SettingKind.Text),
    next("god-mode", SettingKind.Bool),
    next("goal-escalation-model", SettingKind.Text),
    next("memory", SettingKind.Bool),
    next("embedding-model", SettingKind.Text),
    next("embedding-base-url", SettingKind.Text),
    next("embedding-dimensions", SettingKind.Int),
    next("token-view-key", SettingKind.Text),
    next("auto-exit-token-view", SettingKind.Bool),
    next("mcp-config", SettingKind.Text),
    next("sessions-dir", SettingKind.Text),
    next("agents-dir", SettingKind.Text),
    next("schedule-dir", SettingKind.Text),
    next("plans-dir", SettingKind.Text),
    next("hub-port", SettingKind.Int),
    next("no-remote", SettingKind.Bool),
)

/** Per-run (`session`) or secret (API keys stay in env vars or flags, never in a plain file). */
val EXCLUDED_FROM_SETTINGS = setOf("session", "api-key", "brave-api-key")

/** `~/.sophi/settings.json`: a flat object from setting key to string value. Keys it doesn't know are kept. */
class CliSettingsStore(val file: Path = DEFAULT_SETTINGS_FILE, private val onWarning: (String) -> Unit) {
    fun saved(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return runCatching { Json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content } }
            .getOrElse { onWarning("settings: $file isn't valid JSON (${it.message?.lineSequence()?.first()}) — using defaults"); emptyMap() }
    }

    /** Why [value] doesn't fit [setting], or null. */
    fun validate(setting: Setting, value: String): String? = when (val kind = setting.kind) {
        SettingKind.Bool -> if (value.lowercase() in BOOLS) null else "${setting.key} is on or off"
        is SettingKind.Choice -> if (value in kind.values) null else "${setting.key} is one of ${kind.values.joinToString("|")}"
        SettingKind.Int -> if ((value.toIntOrNull() ?: -1) >= 0) null else "${setting.key} is a whole number"
        SettingKind.Text -> null
    }

    /** The form the file and Clikt read: booleans as true/false. */
    fun normalise(setting: Setting, value: String): String =
        if (setting.kind == SettingKind.Bool) (value.lowercase() in TRUE).toString() else value

    fun save(key: String, value: String) = write(saved() + (key to value))
    fun reset(key: String) = write(saved() - key)

    private fun write(values: Map<String, String>) {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, "settings", ".json")
        tmp.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(values.mapValues { JsonPrimitive(it.value) })))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        val TRUE = setOf("true", "on")
        val BOOLS = TRUE + setOf("false", "off")
    }
}

/**
 * Saved settings become this command's option defaults: a flag beats the file, the file beats the
 * built-in default. Subcommand options are keyed `goal.run.model`, so only the root reads the file.
 */
@OptIn(ExperimentalValueSourceApi::class)
fun CliktCommand.readSettingsFrom(store: CliSettingsStore) {
    context { valueSource = MapValueSource(store.saved()) }
}

/** After parsing: each option's value this run, by long name without `--` (null as ""). */
fun CliktCommand.effectiveValues(): Map<String, String> = registeredOptions()
    .filterIsInstance<OptionDelegate<*>>()
    .associate { o -> o.names.maxBy { it.length }.removePrefix("--") to (runCatching { o.value }.getOrNull()?.toString() ?: "") }

/** Where [key]'s value this run came from. */
fun origin(key: String, argv: List<String>, store: CliSettingsStore): String = when {
    argv.any { it == "--$key" || it.startsWith("--$key=") } -> "flag"
    key in store.saved() -> "saved"
    else -> "default"
}

/** One line on where `bash` runs. */
fun sandboxStatus(sb: BashSandbox): String {
    val net = if (sb.network) "network on" else "network off"
    return when (sb.mode) {
        SandboxMode.OFF -> "Sandbox: off — bash runs unsandboxed"
        SandboxMode.SEATBELT -> "Sandbox: seatbelt, $net — bash can write only to the workspace, temp folders and tool caches"
        SandboxMode.DOCKER -> "Sandbox: docker (${sb.image}), $net — bash runs in a container; only the workspace is shared"
    }
}
