package dev.sophi.memory.jane

import kotlin.math.min

/**
 * Confidence-weighted trait store (spec §4.4). Never decays; changes only on encoding
 * evidence or direct user action. Boundary rule: this holds facts about the user's life;
 * sophi-learning's PreferenceStore holds feedback about the assistant's behavior.
 */
class UserProfile(private val store: PalaceStore) {

    fun all(): Map<String, ProfileAttribute> = store.attributes()

    fun view(floor: Double = 0.7): List<ProfileAttribute> =
        all().values.filter { it.confidence >= floor }.sortedBy { it.path }

    fun observeEvidence(path: String, value: String, memoryId: String, nowMs: Long) {
        val existing = all()[path]
        val updated = when {
            existing == null -> ProfileAttribute(path, value, 0.5, 1, listOf(memoryId), nowMs)
            existing.value.equals(value, ignoreCase = true) -> existing.copy(
                confidence = min(1.0, existing.confidence + 0.15),
                evidenceCount = existing.evidenceCount + 1,
                evidenceMemoryIds = existing.evidenceMemoryIds + memoryId,
                updatedAt = nowMs
            )
            else -> {
                val lowered = existing.confidence - 0.25
                if (lowered < 0.3) ProfileAttribute(path, value, 0.5, 1, listOf(memoryId), nowMs)
                else existing.copy(confidence = lowered, updatedAt = nowMs)
            }
        }
        store.upsertAttribute(updated)
    }

    fun confirm(path: String): Boolean = mutate(path) { it.copy(confidence = 1.0) }

    fun correct(path: String, value: String): Boolean = mutate(path) {
        it.copy(value = value, confidence = 0.8, evidenceMemoryIds = emptyList())
    }

    fun delete(path: String): Boolean = mutate(path) { it.copy(deleted = true) }

    /**
     * Deletion propagation (spec §5): remove [memoryId] from every attribute's evidence.
     * Confidence scales by remaining/original evidence; sole-evidence attributes are deleted.
     * Returns affected paths.
     */
    fun reduceEvidence(memoryId: String, nowMs: Long): List<String> =
        all().values.filter { memoryId in it.evidenceMemoryIds }.map { attr ->
            val remaining = attr.evidenceMemoryIds - memoryId
            val updated =
                if (remaining.isEmpty()) attr.copy(deleted = true, updatedAt = nowMs)
                else attr.copy(
                    evidenceMemoryIds = remaining,
                    evidenceCount = remaining.size,
                    confidence = attr.confidence * remaining.size / attr.evidenceMemoryIds.size,
                    updatedAt = nowMs
                )
            store.upsertAttribute(updated)
            attr.path
        }

    private fun mutate(path: String, f: (ProfileAttribute) -> ProfileAttribute): Boolean {
        val attr = all()[path] ?: return false
        store.upsertAttribute(f(attr))
        return true
    }
}
