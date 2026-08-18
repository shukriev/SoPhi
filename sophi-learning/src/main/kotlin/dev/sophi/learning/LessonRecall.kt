package dev.sophi.learning

interface LessonRecall {
    fun recall(scope: String, budgetTokens: Int, query: String? = null): List<Lesson>
}

class RecencyUsageRecall(
    private val store: LessonStore,
    private val maxRecalled: Int = 10
) : LessonRecall {
    override fun recall(scope: String, budgetTokens: Int, query: String?): List<Lesson> {
        val ranked = store.activeIncludingGlobal(scope).sortedWith(
            compareByDescending<Lesson> { it.kind == "preference" }
                .thenByDescending { it.scope == scope }
                .thenByDescending { it.useCount }
                .thenByDescending { it.ts }
        )
        val out = mutableListOf<Lesson>()
        var chars = 0
        val budgetChars = budgetTokens * 4
        for (l in ranked) {
            if (out.size >= maxRecalled) break
            // Skip (not break): a lesson too big to fit alone must not block smaller,
            // lower-ranked lessons that would still fit within the remaining budget.
            if (chars + l.text.length > budgetChars) continue
            out.add(l); chars += l.text.length
        }
        return out
    }
}

class SemanticRecall(
    private val embeddings: dev.sophi.ai.api.EmbeddingProvider,
    private val store: LessonStore,
    private val maxRecalled: Int = 10
) : LessonRecall {
    private val vectorCache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()

    override fun recall(scope: String, budgetTokens: Int, query: String?): List<Lesson> =
        if (query == null) RecencyUsageRecall(store, maxRecalled).recall(scope, budgetTokens, null)
        else kotlinx.coroutines.runBlocking { recallByQuery(scope, budgetTokens, query) }

    private suspend fun recallByQuery(scope: String, budgetTokens: Int, query: String): List<Lesson> {
        val candidates = store.activeIncludingGlobal(scope)
        if (candidates.isEmpty()) return emptyList()
        val queryVector = embeddings.embed(listOf(query)).first()
        val ranked = candidates
            .associateWith { vectorFor(it) }
            .entries.sortedByDescending { (_, v) -> cosine(queryVector, v) }
            .map { it.key }
        return fillBudget(ranked, budgetTokens)
    }

    private suspend fun vectorFor(lesson: Lesson): FloatArray =
        vectorCache.getOrPut(lesson.id) { embeddings.embed(listOf(lesson.text)).first() }

    private fun fillBudget(ranked: List<Lesson>, budgetTokens: Int): List<Lesson> {
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

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}
