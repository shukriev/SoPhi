package dev.sophi.context

import dev.sophi.store.arcade.ArcadeStore
import kotlin.math.ln

data class SearchHit(
    val projectId: String, val path: String, val startLine: Int, val endLine: Int,
    val label: String?, val role: String, val score: Double, val snippet: String
)

/** Ranking knobs (spec §6.5); defaults chosen by the M1 eval (evals/context/RESULTS.md), not user settings. */
data class Weights(
    val k1: Double = 1.2,
    val b: Double = 0.5,
    val rolePrior: Map<String, Double> = mapOf("SOURCE" to 1.0, "INSTRUCTION" to 1.0, "DOC" to 0.9, "CONFIG" to 0.9, "TEST" to 0.6, "GENERATED" to 0.2),
    val nameBoost: Double = 1.5,
    val perDirectory: Int = 3,
    val candidates: Int = 300
)

/**
 * ArcadeDB's FULL_TEXT `$score` only counts matched terms (bake-off: MRR 0.67), so it picks the
 * candidates and BM25 here ranks them (MRR 0.80, equal to SQLite FTS5 and Lucene).
 */
class Retriever(private val store: ArcadeStore, private val weights: Weights = Weights()) {

    fun search(query: String, projectIds: List<String>, limit: Int = 8): List<SearchHit> {
        val terms = Tokenizer.tokens(query).distinct()
        if (terms.isEmpty() || projectIds.isEmpty()) return emptyList()
        val rows = store.fullText(
            ContextSchema.CHUNK, "toks", terms.joinToString(" "),
            fields = listOf("projectId", "path", "startLine", "endLine", "label", "role", "toks", "len", "text"),
            limit = weights.candidates, where = "projectId IN ?", params = listOf(projectIds)
        )
        if (rows.isEmpty()) return emptyList()
        val meta = ContextSchema.meta(store) ?: return emptyList()
        val n = meta.chunkCount.toDouble().coerceAtLeast(1.0)
        val avgdl = (meta.totalLen.toDouble() / n).coerceAtLeast(1.0)
        val df = store.query("SELECT id, df FROM ${ContextSchema.TERM} WHERE id IN ?", terms)
            .associate { it["id"] as String to (it["df"] as Number).toDouble() }

        val scored = rows.map { r ->
            val toks = (r["toks"] as String).split(' ')
            val tf = HashMap<String, Int>(); toks.forEach { if (it in df) tf.merge(it, 1, Int::plus) }
            val len = (r["len"] as Number).toDouble()
            var s = 0.0
            for (t in terms) {
                val f = tf[t] ?: continue
                val d = df[t] ?: continue
                val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
                s += idf * f * (weights.k1 + 1) / (f + weights.k1 * (1 - weights.b + weights.b * len / avgdl))
            }
            val path = r["path"] as String
            val label = r["label"] as String?
            val nameTerms = Tokenizer.tokens(listOfNotNull(label, path.substringAfterLast('/')).joinToString(" ")).toSet()
            if (terms.any { it in nameTerms }) s *= weights.nameBoost
            s *= weights.rolePrior[r["role"] as String] ?: 1.0
            SearchHit(r["projectId"] as String, path, (r["startLine"] as Number).toInt(), (r["endLine"] as Number).toInt(),
                label, r["role"] as String, s, snippet(r["text"] as String))
        }.filter { it.score > 0 }

        val perDir = HashMap<String, Int>()
        return scored.sortedByDescending { it.score }
            .distinctBy { it.projectId to it.path }
            .filter { h -> perDir.merge(h.projectId + "/" + h.path.substringBeforeLast('/', ""), 1, Int::plus)!! <= weights.perDirectory }
            .take(limit)
    }

    private fun snippet(text: String) = text.lines().filter { it.isNotBlank() }.take(2).joinToString("\n") { it.trim().take(160) }
}
