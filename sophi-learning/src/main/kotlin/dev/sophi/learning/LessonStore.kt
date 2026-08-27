package dev.sophi.learning

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import kotlinx.serialization.json.Json

class LessonStore(
    private val log: JsonlLog,
    private val maxActivePerScope: Int = 50,
    private val versionStore: VersionStore? = null
) {
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
        recordVersion(lesson, ProducedBy.REFLECTION)
        val actives = active(lesson.scope)
        if (actives.size > maxActivePerScope) {
            actives.minWithOrNull(compareBy({ it.useCount }, { it.ts }))?.let { archive(it.id) }
        }
    }

    /** @return true if [id] matched an active lesson that was archived; false if it was unknown or already archived. */
    fun archive(id: String): Boolean {
        val current = fold()[id] ?: return false
        if (current.status != "active") return false
        val archived = current.copy(status = "archived", ts = System.currentTimeMillis())
        append(archived)
        recordVersion(archived, ProducedBy.HUMAN)
        return true
    }

    fun bumpUse(lessons: List<Lesson>) {
        lessons.forEach {
            val bumped = it.copy(useCount = it.useCount + 1)
            append(bumped)
            recordVersion(bumped, ProducedBy.HUMAN)
        }
    }

    private fun append(l: Lesson) = runCatching { log.append(json.encodeToString(Lesson.serializer(), l)) }

    private fun recordVersion(lesson: Lesson, producedBy: ProducedBy) {
        versionStore?.record(ArtifactType.LESSON, lesson.id, json.encodeToString(Lesson.serializer(), lesson), producedBy)
    }
}
