package dev.sophi.cli

import dev.sophi.core.tools.BashSandbox
import dev.sophi.core.tools.DEFAULT_DOCKER_IMAGE
import dev.sophi.core.tools.SandboxMode
import dev.sophi.core.tools.ToggleableConfirmationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rows the menu shows at once; it scrolls with the cursor so a short terminal isn't overrun. */
private const val VISIBLE_ROWS = 12

/** What the built-in default is for the live settings, so `d` can put the session back. */
private val LIVE_DEFAULTS = mapOf("sandbox" to "off", "network" to "true", "docker-image" to DEFAULT_DOCKER_IMAGE, "auto" to "false")

/**
 * Reads and changes the CLI's settings: live ones ([Setting.live]) change this session at once,
 * every change is saved to [store] for the next start.
 */
class SettingsController(
    val store: CliSettingsStore,
    private val argv: List<String>,
    /** Each option's value this run ([effectiveValues]). */
    private val thisRun: Map<String, String>,
    /** Each option's help text, by key. */
    private val help: Map<String, String>,
    private val bashSandbox: BashSandbox?,
    private val autoToggle: ToggleableConfirmationPolicy?,
    val settings: List<Setting> = CLI_SETTINGS,
    /** False: no raw-mode terminal, so `/settings` lists instead of opening the menu. */
    val interactive: Boolean = false,
) {
    /** Changed or reset in this session: their origin is no longer what the command line said. */
    private val touched = mutableSetOf<String>()

    fun help(s: Setting): String = help[s.key] ?: ""

    fun current(s: Setting): String = when (s.key) {
        "sandbox" -> bashSandbox?.mode?.name?.lowercase()
        "network" -> bashSandbox?.network?.toString()
        "docker-image" -> bashSandbox?.image
        "auto" -> autoToggle?.autoModeEnabled?.toString()
        else -> null
    } ?: store.saved()[s.key] ?: if (s.key in touched) "(default)" else thisRun[s.key] ?: ""

    fun origin(s: Setting): String = when {
        s.key !in touched -> origin(s.key, argv, store)
        s.key in store.saved() -> "saved"
        else -> "default"
    }

    /** Changed in this session and only takes effect when `sophi` starts again. */
    fun pendingRestart(s: Setting): Boolean = !s.live && s.key in touched

    /** Applies [raw] (live settings) and saves it; the reason when it doesn't fit or can't apply, and then nothing changed. */
    suspend fun commit(s: Setting, raw: String): String? {
        if (s.kind == SettingKind.Text && raw.isBlank()) return reset(s)
        store.validate(s, raw)?.let { return it }
        store.cannotSave()?.let { return it }
        val value = store.normalise(s, raw)
        if (s.live) apply(s.key, value)?.let { return it }
        store.save(s.key, value)?.let { return it }
        touched += s.key
        return null
    }

    /** Back to the built-in default: removed from the file, and a live setting applied now. */
    suspend fun reset(s: Setting): String? {
        store.cannotSave()?.let { return it }
        if (s.live) apply(s.key, LIVE_DEFAULTS.getValue(s.key))?.let { return it }
        store.reset(s.key)?.let { return it }
        touched += s.key
        return null
    }

    // set() may start a Docker container, which can take minutes on the first pull
    private suspend fun apply(key: String, value: String): String? = withContext(Dispatchers.IO) {
        if (key == "auto") {
            val toggle = autoToggle ?: return@withContext "auto mode is fixed under --god-mode"
            toggle.autoModeEnabled = value.toBoolean()
            return@withContext null
        }
        val sb = bashSandbox ?: return@withContext "the bash sandbox isn't available in this session"
        when (key) {
            "sandbox" -> sb.set(mode = SandboxMode.valueOf(value.uppercase()))
            "network" -> sb.set(network = value.toBoolean())
            else -> sb.set(image = value)
        }
    }

    /** True when committing [value] to [s] starts a container, so the caller can say so first. */
    fun startsContainer(s: Setting, value: String): Boolean =
        (s.key == "sandbox" && value == "docker") ||
            (s.key in setOf("network", "docker-image") && bashSandbox?.mode == SandboxMode.DOCKER)

    fun row(s: Setting): String {
        val value = current(s).let { if (s.kind == SettingKind.Bool) if (it == "true") "on" else if (it == "false") "off" else it else it }
        return "${s.key.padEnd(24)}${value.ifEmpty { "(unset)" }.padEnd(30)} [${origin(s)}]" + if (pendingRestart(s)) " (next start)" else ""
    }
}

/** The `/settings` screen as a state machine: keys in, text out. [redraw] shows a status before a slow commit. */
class SettingsMenu(private val ctl: SettingsController, private val redraw: (String) -> Unit = {}) {
    private val settings = ctl.settings
    private var cursor = 0
    private var editing: StringBuilder? = null
    private var message: String? = null

    suspend fun onKey(k: TurnKey) {
        val s = settings[cursor]
        val edit = editing
        if (edit != null) {
            when (k) {
                is TurnKey.Text -> edit.append(k.ch)
                TurnKey.Backspace -> if (edit.isNotEmpty()) edit.setLength(edit.length - 1)
                TurnKey.Enter -> { editing = null; commit(s, edit.toString()) }
                else -> {}
            }
            return
        }
        message = null
        when (k) {
            TurnKey.Up -> cursor = (cursor - 1).coerceAtLeast(0)
            TurnKey.Down -> cursor = (cursor + 1).coerceAtMost(settings.size - 1)
            TurnKey.Enter -> when (val kind = s.kind) {
                SettingKind.Bool -> commit(s, (ctl.current(s) != "true").toString())
                is SettingKind.Choice -> commit(s, kind.values[(kind.values.indexOf(ctl.current(s)) + 1) % kind.values.size])
                else -> editing = StringBuilder(ctl.current(s).removeSurrounding("(", ")").takeUnless { it == "default" } ?: "")
            }
            TurnKey.Text('d') -> ctl.reset(s)?.let { message = "✗ $it" }
            else -> {}
        }
    }

    /** Esc: cancels an edit (false, the menu stays) or closes the menu (true). */
    fun onEsc(): Boolean {
        if (editing == null) return true
        editing = null
        return false
    }

    private suspend fun commit(s: Setting, value: String) {
        if (ctl.startsContainer(s, value)) { message = "Starting a container (the first run pulls the image; keys wait until it's done)…"; redraw(render()) }
        message = ctl.commit(s, value)?.let { "✗ $it" }
    }

    fun render(): String = buildString {
        appendLine("Settings  (↑↓ move · Enter change · d reset · Esc close)")
        val first = (cursor - VISIBLE_ROWS / 2).coerceIn(0, (settings.size - VISIBLE_ROWS).coerceAtLeast(0))
        settings.subList(first, (first + VISIBLE_ROWS).coerceAtMost(settings.size)).forEachIndexed { i, s ->
            val here = first + i == cursor
            val edit = editing
            append(if (here) "> " else "  ")
            appendLine(if (here && edit != null) "${s.key.padEnd(24)}${edit}_" else ctl.row(s))
        }
        val s = settings[cursor]
        append("  ").append(ctl.help(s).ifEmpty { s.key }).append(if (s.live) "" else "  (applies next start)")
        message?.let { append("\n  ").append(it) }
    }
}

/** Shows [menu] in [liveRegion] until Esc closes it. */
suspend fun runSettingsMenu(menu: SettingsMenu, input: InputSource, liveRegion: LiveRegion) {
    liveRegion.update(menu.render())
    while (true) {
        input.awaitControlKeys('\u0000', onToggle = {}) { k -> menu.onKey(k); liveRegion.update(menu.render()) }
        if (menu.onEsc()) break
        liveRegion.update(menu.render())
    }
    liveRegion.clear()
}
