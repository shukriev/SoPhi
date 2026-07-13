package dev.sophi.learning

import kotlinx.serialization.json.Json

class PreferenceStore(private val log: JsonlLog) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fold(): Map<String, PreferenceRecord> =
        log.readAll().mapNotNull {
            runCatching { json.decodeFromString(PreferenceRecord.serializer(), it) }.getOrNull()
        }.associateBy { it.id }

    fun add(record: PreferenceRecord) = append(record)

    fun active(scope: String): List<PreferenceRecord> =
        fold().values.filter { it.scope == scope && it.status == "active" }.sortedBy { it.ts }

    /** Scope-agnostic, non-tombstoned records across all scopes, sorted by ts. Used by the export pipeline. */
    fun activeAll(): List<PreferenceRecord> =
        fold().values.filter { it.status == "active" }.sortedBy { it.ts }

    fun forSession(sessionId: String): List<PreferenceRecord> =
        fold().values.filter { it.sessionId == sessionId && it.status == "active" }.sortedBy { it.ts }

    /** @return true if [id] matched an active record that was deleted; false if it was unknown or already deleted. */
    fun delete(id: String): Boolean {
        val current = fold()[id] ?: return false
        if (current.status != "active") return false
        append(current.copy(status = "deleted", ts = System.currentTimeMillis()))
        return true
    }

    fun link(negativeId: String, positiveId: String) {
        val all = fold()
        all[negativeId]?.let { append(it.copy(pairedWith = positiveId)) }
        all[positiveId]?.let { append(it.copy(pairedWith = negativeId)) }
    }

    private fun append(r: PreferenceRecord) =
        runCatching { log.append(json.encodeToString(PreferenceRecord.serializer(), r)) }
}
