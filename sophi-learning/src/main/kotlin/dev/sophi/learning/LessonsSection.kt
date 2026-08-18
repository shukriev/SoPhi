package dev.sophi.learning

class LessonsSection(
    private val recall: LessonRecall,
    private val store: LessonStore,
    private val config: LearningConfig
) {
    fun render(scope: String, query: String? = null): String? {
        val recalled = recall.recall(scope, config.lessonTokenBudget, query)
        if (recalled.isEmpty()) return null
        store.bumpUse(recalled)
        return buildString {
            appendLine("## Lessons from previous sessions (this project)")
            // Truncated defensively: lesson text is distilled by an LLM from session content,
            // so one oversized or malformed entry must not be able to dominate the injected prompt.
            recalled.forEach { appendLine("- ${it.text.take(300)}") }
        }.trimEnd()
    }
}
