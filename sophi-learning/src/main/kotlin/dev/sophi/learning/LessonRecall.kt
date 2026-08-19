package dev.sophi.learning

interface LessonRecall {
    suspend fun recall(scope: String, budgetTokens: Int, query: String? = null): List<Lesson>
}

class RecencyUsageRecall(
    private val store: LessonStore,
    private val maxRecalled: Int = 10
) : LessonRecall {
    override suspend fun recall(scope: String, budgetTokens: Int, query: String?): List<Lesson> {
        val ranked = store.activeIncludingGlobal(scope).sortedWith(
            compareByDescending<Lesson> { it.kind == "preference" }
                .thenByDescending { it.scope == scope }
                .thenByDescending { it.useCount }
                .thenByDescending { it.ts }
        )
        return fillBudget(ranked, budgetTokens, maxRecalled)
    }
}

class SemanticRecall(
    private val embeddings: dev.sophi.ai.api.EmbeddingProvider,
    private val store: LessonStore,
    private val maxRecalled: Int = 10
) : LessonRecall {
    private val vectorCache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()

    override suspend fun recall(scope: String, budgetTokens: Int, query: String?): List<Lesson> =
        if (query == null) RecencyUsageRecall(store, maxRecalled).recall(scope, budgetTokens, null)
        else recallByQuery(scope, budgetTokens, query)

    private suspend fun recallByQuery(scope: String, budgetTokens: Int, query: String): List<Lesson> {
        val candidates = store.activeIncludingGlobal(scope)
        if (candidates.isEmpty()) return emptyList()
        val queryVector = embeddings.embed(listOf(query)).first()
        // Batched: one round trip for every cache-miss lesson, not one round trip per lesson.
        val missing = candidates.filter { vectorCache[it.id] == null }
        if (missing.isNotEmpty()) {
            val vectors = embeddings.embed(missing.map { it.text })
            missing.forEachIndexed { i, lesson -> vectorCache[lesson.id] = vectors[i] }
        }
        val ranked = candidates
            .associateWith { vectorCache.getValue(it.id) }
            .entries.sortedByDescending { (_, v) -> cosine(queryVector, v) }
            .map { it.key }
        return fillBudget(ranked, budgetTokens, maxRecalled)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}

/** Shared by both [RecencyUsageRecall] and [SemanticRecall]: caps recalled lessons at
 *  [maxRecalled], skipping (not stopping at) any lesson too large to fit in [budgetTokens] alone
 *  so smaller, lower-ranked lessons still get a chance. */
private fun fillBudget(ranked: List<Lesson>, budgetTokens: Int, maxRecalled: Int): List<Lesson> {
    val out = mutableListOf<Lesson>()
    var chars = 0
    val budgetChars = budgetTokens * 4
    for (l in ranked) {
        if (out.size >= maxRecalled) break
        if (chars + l.text.length > budgetChars) continue
        out.add(l); chars += l.text.length
    }
    return out
}
