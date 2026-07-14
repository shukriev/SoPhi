package dev.sophi.memory.jane

import dev.sophi.memory.ForgetRequest
import dev.sophi.memory.ForgetResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * "Deleted means gone — provably unretrievable" (spec §5). User-initiated forget is a
 * compacting rewrite, never a tombstone. The audit records THAT a forget happened, not what.
 */
class ForgetEngine(
    private val store: PalaceStore,
    private val index: EmbeddingIndex,
    private val profile: UserProfile
) {
    fun forget(request: ForgetRequest, nowMs: Long): ForgetResult = when (request) {
        is ForgetRequest.All -> {
            val ids = store.memories().keys.toList()
            store.wipe()
            ids.forEach { index.remove(it) }
            ForgetResult(ids, 0, emptyList())
        }
        is ForgetRequest.ById -> forgetOne(request.id, nowMs)
    }

    private fun forgetOne(id: String, nowMs: Long): ForgetResult {
        val all = store.memories()
        if (id !in all) return ForgetResult(emptyList(), 0, emptyList())

        val edges = store.edges()
        val incoming = edges.filter { it.toId == id }
        val outgoing = edges.filter { it.fromId == id }
        val untouched = edges.filter { it.toId != id && it.fromId != id }
        // Re-link predecessor -> successor, marked compressed (spec §5).
        val relinked = incoming.flatMap { inc ->
            outgoing.map { out -> CausalEdge(inc.fromId, out.toId, inc.threadLabel, compressed = true) }
        }

        val affectedPaths = profile.reduceEvidence(id, nowMs)
        val keptMemories = (all - id).values
        val keptEmbeddings = store.embeddings() - id
        val keptRecalls = store.recallsSince(0L).filter { it.memoryId != id }

        store.rewriteAll(keptMemories, untouched + relinked, store.attributes().values,
            keptEmbeddings, store.embeddingModel(), keptRecalls)
        index.remove(id)
        store.deleteLastRecall()
        store.appendAudit(buildJsonObject {
            put("ts", JsonPrimitive(nowMs)); put("event", JsonPrimitive("forget")); put("count", JsonPrimitive(1))
        }.toString())
        return ForgetResult(listOf(id), relinked.size, affectedPaths)
    }

    /** Consolidation purge: physically drop soft-deleted memories older than [cutoffMs]. */
    fun purgeSoftDeleted(cutoffMs: Long, nowMs: Long): Int {
        val victims = store.memories().values.filter { it.softDeletedAt != null && it.softDeletedAt!! < cutoffMs }
        victims.forEach { forgetOne(it.id, nowMs) }
        return victims.size
    }
}
