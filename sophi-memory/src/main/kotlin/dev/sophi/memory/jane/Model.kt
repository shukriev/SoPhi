package dev.sophi.memory.jane

import kotlinx.serialization.Serializable

@Serializable enum class Room { ENTITIES, TASKS, EPISODES, KNOWLEDGE, NARRATIVE }
@Serializable enum class Sensitivity { PUBLIC, PERSONAL, SENSITIVE, RESTRICTED }
@Serializable enum class Provenance { USER_DIRECT, USER_ARTIFACT, THIRD_PARTY, SYSTEM_INFERRED }

@Serializable
data class SalienceSignals(val rep: Double, val emph: Double, val nov: Double, val aff: Double, val rec: Double)

@Serializable
data class Memory(
    val id: String,
    val text: String,
    val room: Room,
    val salience: Double,
    val signals: SalienceSignals,
    val sensitivity: Sensitivity,
    val provenance: Provenance,
    val createdAt: Long,
    val reinforcedAt: Long,
    val sourceSessionId: String,
    val supersededBy: String? = null,
    val softDeletedAt: Long? = null
) {
    /** Visible to retrieval and default browse. */
    val active: Boolean get() = supersededBy == null && softDeletedAt == null
}

@Serializable
data class CausalEdge(
    val fromId: String,          // cause
    val toId: String,            // effect
    val threadLabel: String,
    val compressed: Boolean = false,
    val removed: Boolean = false // append-only soft removal; hard forget rewrites the file
) {
    val key: String get() = "$fromId->$toId"
}

@Serializable
data class ProfileAttribute(
    val path: String,
    val value: String,
    val confidence: Double,
    val evidenceCount: Int,
    val evidenceMemoryIds: List<String>,
    val updatedAt: Long,
    val deleted: Boolean = false
)

@Serializable
data class RecallRecord(val ts: Long, val memoryId: String, val sessionId: String)
