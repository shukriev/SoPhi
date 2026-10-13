package dev.sophi.context

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.ContextContributor
import dev.sophi.extensions.Placement
import dev.sophi.extensions.SophiPlugin

private val IDENTIFIER = Regex(
    """[a-z][a-z0-9]*[A-Z]\w*|[A-Z][a-z0-9]+[A-Z]\w*|\w+_\w+|[\w/-]+\.(kt|kts|java|ts|tsx|js|py|go|rs|md|json|ya?ml|xml|toml|gradle|sh)\b"""
)

/** True if the raw message names a code identifier or file (camelCase, snake_case, file.ext). */
fun hasIdentifier(message: String): Boolean = IDENTIFIER.containsMatchIn(message)

/** Confidence gate for the per-turn hint; defaults are calibrated by the M2 eval. */
data class HintGate(val minTerms: Int = 3, val minScore: Double = DEFAULT_MIN_SCORE, val minRatio: Double = DEFAULT_MIN_RATIO) {
    companion object { const val DEFAULT_MIN_SCORE = 10.0; const val DEFAULT_MIN_RATIO = 1.0 }

    fun fires(message: String, hits: List<SearchHit>): Boolean =
        Tokenizer.tokens(message).toSet().size >= minTerms && hits.isNotEmpty() && hits[0].score >= minScore &&
            (hits.size == 1 || hits[0].score / hits[1].score >= minRatio || hasIdentifier(message))
}

/** Volatile per-turn pointer to at most three likely files, only when retrieval is confident. Never waits for the engine. */
class HintPlugin(
    private val engine: ContextEngine, private val scope: () -> List<String>, private val budget: ContextBudget,
    private val gate: HintGate = HintGate(), private val mode: String = "gated"
) : SophiPlugin, ContextContributor {
    override val name = "hint"
    override val placement = Placement.VOLATILE
    override fun hooks(): List<AgentHook> = emptyList()

    override suspend fun contribute(sessionId: String, userInput: String): String? {
        if (mode == "off" || !engine.ready) return null
        return try {
            val ids = scope().distinct()
            if (ids.isEmpty()) return null
            val hits = engine.search(userInput, ids, 3) ?: return null
            if (mode != "always" && !gate.fires(userInput, hits)) return null
            if (hits.isEmpty()) return null
            val projects = engine.projectsOrNull() ?: return null
            val multi = ids.size > 1
            fun clean(t: String) = t.replace('\r', ' ').replace('\n', ' ').replace('<', '‹').replace('>', '›').trim()
            val summaries = hits.map { it.projectId }.distinct().associateWith { engine.fileSummariesOrNull(it) }
            val lines = hits.take(3).map { h ->
                val summary = clean(summaries[h.projectId]?.firstOrNull { it.first == h.path }?.third?.takeIf { it.isNotBlank() }
                    ?: h.snippet.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
                val shown = clean(if (multi) "${projects.firstOrNull { it.id == h.projectId }?.name ?: h.projectId}:${h.path}" else h.path)
                "$shown:${h.startLine} — ${clean(h.label ?: "-")}" + if (summary.isEmpty()) "" else " — $summary"
            }
            val head = "<context_hint>Files likely relevant to this message (from the project index — read them before relying on them):"
            val tail = "</context_hint>"
            val kept = lines.toMutableList()
            fun render() = (listOf(head) + kept).joinToString("\n") + tail
            while (kept.isNotEmpty() && render().length > budget.hintChars) kept.removeAt(kept.size - 1)
            if (kept.isEmpty()) null else render()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e
        } catch (e: Exception) { null }
    }
}
