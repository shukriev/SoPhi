package dev.sophi.context

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.ContextContributor
import dev.sophi.extensions.Placement
import dev.sophi.extensions.SophiPlugin
import java.util.concurrent.atomic.AtomicBoolean

/** Stable system-prompt section: project conventions + map. Waits for the engine only on its first call. */
class OrientationPlugin(
    private val engine: ContextEngine, private val scope: () -> List<String>, private val budget: ContextBudget,
    private val mode: String = "both", private val firstWaitMillis: Long = 1_000
) : SophiPlugin, ContextContributor {
    override val name = "orientation"
    override val placement = Placement.STABLE
    override fun hooks(): List<AgentHook> = emptyList()
    private val first = AtomicBoolean(true)

    override suspend fun contribute(sessionId: String, userInput: String): String? {
        if (mode == "off") return null
        return try {
            if (first.compareAndSet(true, false)) engine.awaitReady(firstWaitMillis)
            if (!engine.ready) return null
            val ids = scope().toSet()
            val project = engine.projectsOrNull()?.firstOrNull { it.id in ids } ?: return null
            val files = engine.fileSummariesOrNull(project.id) ?: return null
            Orientation.render(project, files, budget.orientationChars, mode)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e
        } catch (e: Exception) { null }
    }
}
