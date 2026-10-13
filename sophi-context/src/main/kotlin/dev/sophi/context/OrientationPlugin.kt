package dev.sophi.context

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.ContextContributor
import dev.sophi.extensions.Placement
import dev.sophi.extensions.SophiPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stable system-prompt section: project conventions + map. Waits for the engine only on its first call.
 * The last good render is kept per project and reused while its key (scan time + instruction-file stamps) is
 * unchanged, and returned when the store fails, so the cached prompt prefix neither churns nor loses the section.
 */
class OrientationPlugin(
    private val engine: ContextEngine, private val project: ProjectOf, private val budget: ContextBudget,
    mode: String = "both", private val firstWaitMillis: Long = 1_000,
    private val globalInstructions: java.nio.file.Path? = Orientation.defaultGlobal()
) : SophiPlugin, ContextContributor {
    private val mode = normalizeKnob(mode, "both", "instructions", "map", "off")
    override val name = "orientation"
    override val placement = Placement.STABLE
    override fun hooks(): List<AgentHook> = emptyList()
    private val first = AtomicBoolean(true)
    private class Rendered(val key: String, val text: String?)
    private val last = java.util.concurrent.ConcurrentHashMap<String, Rendered>()

    private fun stamp(p: java.nio.file.Path?): String =
        (try { p?.let { "${java.nio.file.Files.getLastModifiedTime(it).toMillis()}/${java.nio.file.Files.size(it)}" } } catch (e: java.io.IOException) { null }).toString()

    override suspend fun contribute(sessionId: String, userInput: String): String? {
        if (mode == "off") return null
        return try {
            if (first.compareAndSet(true, false)) runInterruptible(Dispatchers.IO) { engine.awaitReady(firstWaitMillis) }
            val primary = project(sessionId) ?: return null
            if (!engine.ready) return fallback(primary)
            val projects = engine.projectsOrNull() ?: return fallback(primary)
            val project = projects.firstOrNull { it.id == primary } ?: return null
            val root = java.nio.file.Path.of(project.root)
            val key = listOf(project.id, project.lastScanAt, stamp(globalInstructions),
                stamp(root.resolve("AGENTS.md")), stamp(root.resolve("CLAUDE.md")), stamp(root.resolve(".sophi/instructions.md"))).joinToString("|")
            last[project.id]?.takeIf { it.key == key }?.let { return it.text }
            val files = engine.fileSummariesOrNull(project.id) ?: return fallback(primary)
            val text = Orientation.render(project, files, budget.orientationChars, mode, globalInstructions)
            last[project.id] = Rendered(key, text)
            text
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e
        } catch (e: Exception) { project(sessionId)?.let { fallback(it) } }
    }

    /** Store trouble: keep serving the primary project's last good render rather than dropping the section for a turn. */
    private fun fallback(primary: String): String? = last[primary]?.text
}
