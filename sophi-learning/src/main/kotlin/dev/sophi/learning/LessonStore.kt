package dev.sophi.learning

import kotlinx.serialization.json.Json

class LessonStore(private val log: JsonlLog, private val maxActivePerScope: Int = 50) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fold(): Map<String, Lesson> =
        log.readAll().mapNotNull { runCatching { json.decodeFromString(Lesson.serializer(), it) }.getOrNull() }
            .associateBy { it.id }   // later lines overwrite earlier: last wins

    fun active(scope: String): List<Lesson> =
        fold().values.filter { it.scope == scope && it.status == "active" }.sortedBy { it.ts }

    fun activeIncludingGlobal(scope: String): List<Lesson> =
        fold().values.filter { (it.scope == scope || it.scope == "*") && it.status == "active" }.sortedBy { it.ts }

    fun archived(scope: String): List<Lesson> =
        fold().values.filter { it.scope == scope && it.status == "archived" }

    fun add(lesson: Lesson) {
        append(lesson)
        val actives = active(lesson.scope)
        if (actives.size > maxActivePerScope) {
            actives.minWithOrNull(compareBy({ it.useCount }, { it.ts }))?.let { archive(it.id) }
        }
    }

    fun archive(id: String) {
        fold()[id]?.let { append(it.copy(status = "archived", ts = System.currentTimeMillis())) }
    }

    fun bumpUse(lessons: List<Lesson>) {
        lessons.forEach { append(it.copy(useCount = it.useCount + 1)) }
    }

    private fun append(l: Lesson) = runCatching { log.append(json.encodeToString(Lesson.serializer(), l)) }
}
