package dev.sophi.memory

data class RecallQuery(val sessionId: String, val userInput: String, val nowMs: Long)
data class TurnObservation(val sessionId: String, val userInput: String, val assistantReply: String, val nowMs: Long)
data class MemoryBlock(val rendered: String, val memoryIds: List<String>)
data class ConsolidationReport(
    val merged: Int, val strengthened: Int, val compressed: Int, val pruned: Int, val purged: Int
) { val total: Int get() = merged + strengthened + compressed + pruned + purged }

sealed interface ForgetRequest {
    data class ById(val id: String) : ForgetRequest
    data object All : ForgetRequest
}
data class ForgetResult(val removedIds: List<String>, val relinkedEdges: Int, val affectedProfilePaths: List<String>)

data class BrowseFilter(val room: String? = null, val includeHidden: Boolean = false)
data class MemoryView(val id: String, val text: String, val metadata: Map<String, String>)
data class ProfileAttributeView(val path: String, val value: String, val confidence: Double)

sealed interface ProfileAction {
    data class Confirm(val path: String) : ProfileAction
    data class Correct(val path: String, val value: String) : ProfileAction
    data class Delete(val path: String) : ProfileAction
}

/**
 * Technique-agnostic memory SPI (spec §3.1). Deliberately no palace vocabulary —
 * rooms/salience/threads surface only through MemoryView.metadata, rendered generically.
 */
interface MemoryTechnique {
    suspend fun recall(query: RecallQuery): MemoryBlock?
    suspend fun observe(turn: TurnObservation)
    suspend fun consolidate(nowMs: Long): ConsolidationReport
    suspend fun forget(request: ForgetRequest): ForgetResult
    suspend fun search(query: String, k: Int): List<MemoryView>
    fun browse(filter: BrowseFilter): List<MemoryView>
    fun profileView(): List<ProfileAttributeView>
    fun updateProfile(action: ProfileAction): Boolean
    fun explainLastRecall(): String?
}
