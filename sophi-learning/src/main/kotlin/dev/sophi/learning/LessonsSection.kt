package dev.sophi.learning

class LessonsSection(
    private val recall: LessonRecall,
    private val store: LessonStore,
    private val config: LearningConfig
) {
    fun render(scope: String): String? {
        val recalled = recall.recall(scope, config.lessonTokenBudget)
        if (recalled.isEmpty()) return null
        store.bumpUse(recalled)
        return buildString {
            appendLine("## Lessons from previous sessions (this project)")
            recalled.forEach { appendLine("- ${it.text}") }
        }.trimEnd()
    }
}
