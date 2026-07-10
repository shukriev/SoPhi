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

    fun forSession(sessionId: String): List<PreferenceRecord> =
        fold().values.filter { it.sessionId == sessionId && it.status == "active" }.sortedBy { it.ts }

    fun delete(id: String) {
        fold()[id]?.let { append(it.copy(status = "deleted", ts = System.currentTimeMillis())) }
    }

    fun link(sessionId: String, negativeEntryIndex: Int, positiveEntryIndex: Int) {
        val session = forSession(sessionId)
        session.find { it.entryIndex == negativeEntryIndex && it.polarity == "negative" }
            ?.let { append(it.copy(pairedWith = positiveEntryIndex)) }
        session.find { it.entryIndex == positiveEntryIndex && it.polarity == "positive" }
            ?.let { append(it.copy(pairedWith = negativeEntryIndex)) }
    }

    private fun append(r: PreferenceRecord) =
        runCatching { log.append(json.encodeToString(PreferenceRecord.serializer(), r)) }
}
