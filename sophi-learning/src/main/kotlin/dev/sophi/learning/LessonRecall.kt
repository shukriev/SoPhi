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
