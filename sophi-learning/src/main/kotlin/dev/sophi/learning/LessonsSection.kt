package dev.sophi.learning

import kotlinx.serialization.json.Json

class LessonsSection(
    private val recall: LessonRecall,
    private val store: LessonStore,
    private val usageLog: JsonlLog,
    private val config: LearningConfig
) {
    private val json = Json { encodeDefaults = true }

    suspend fun render(scope: String, sessionId: String, query: String? = null): String? {
        val recalled = recall.recall(scope, config.lessonTokenBudget, query)
        if (recalled.isEmpty()) return null
        store.bumpUse(recalled)
        recordUsage(scope, sessionId, recalled)
        return buildString {
            appendLine("## Lessons from previous sessions (this project)")
            // Truncated defensively: lesson text is distilled by an LLM from session content,
            // so one oversized or malformed entry must not be able to dominate the injected prompt.
            recalled.forEach { appendLine("- ${it.text.take(300)}") }
        }.trimEnd()
    }

    /** The keystone attribution record for lessons — see LessonUsageEvent's own doc. */
    private fun recordUsage(scope: String, sessionId: String, recalled: List<Lesson>) {
        recalled.forEach { lesson ->
            runCatching {
                usageLog.append(json.encodeToString(
                    LessonUsageEvent.serializer(),
                    LessonUsageEvent(ts = System.currentTimeMillis(), scope = scope, sessionId = sessionId, lessonId = lesson.id)
                ))
            }
        }
    }
}
