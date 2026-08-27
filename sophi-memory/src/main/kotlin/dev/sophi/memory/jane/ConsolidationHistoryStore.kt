package dev.sophi.memory.jane

import dev.sophi.memory.store.JsonlLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.UUID

@Serializable
data class ConsolidationRecord(
    val ts: Long,
    val merged: Int,
    val strengthened: Int,
    val compressed: Int,
    val pruned: Int,
    val softDeletedIds: List<String>,
    val purgedIds: List<String>,
    val autoPurgeEnabled: Boolean,
    val id: String = "consolidation_" + UUID.randomUUID()
)

class ConsolidationHistoryStore(path: Path) {
    private val log = JsonlLog(path)
    private val json = Json { ignoreUnknownKeys = true }

    fun record(entry: ConsolidationRecord) {
        log.append(json.encodeToString(entry))
    }

    fun all(): List<ConsolidationRecord> =
        log.readAll().mapNotNull { runCatching { json.decodeFromString<ConsolidationRecord>(it) }.getOrNull() }
}
