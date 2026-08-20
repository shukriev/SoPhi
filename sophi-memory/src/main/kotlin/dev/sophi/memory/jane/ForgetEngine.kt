package dev.sophi.memory.jane

import dev.sophi.memory.ForgetRequest
import dev.sophi.memory.ForgetResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * "Deleted means gone — provably unretrievable" (spec §5). The audit records THAT a forget
 * happened, not what.
 */
class ForgetEngine(
    private val store: PalaceStore,
    private val profile: UserProfile
) {
    fun forget(request: ForgetRequest, nowMs: Long): ForgetResult = when (request) {
        is ForgetRequest.All -> {
            val ids = store.memories().keys.toList()
            store.wipe()
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
        // Re-link predecessor -> successor, marked compressed (spec §5).
        val relinked = incoming.flatMap { inc ->
            outgoing.map { out -> CausalEdge(inc.fromId, out.toId, inc.threadLabel, compressed = true) }
        }

        val affectedPaths = profile.reduceEvidence(id, nowMs)

        (incoming + outgoing).forEach { store.deleteEdge(it.fromId, it.toId) }
        relinked.forEach { store.upsertEdge(it) }
        store.deleteMemory(id)
        store.deleteRecallsFor(id)
        store.deleteLastRecall()
        store.appendAudit(buildJsonObject {
            put("ts", JsonPrimitive(nowMs)); put("event", JsonPrimitive("forget")); put("count", JsonPrimitive(1))
        }.toString())
        return ForgetResult(listOf(id), relinked.size, affectedPaths)
    }

    /** Non-mutating preview of what forget(ById(id)) would remove and affect. */
    fun preview(id: String): ForgetResult {
        val all = store.memories()
        if (id !in all) return ForgetResult(emptyList(), 0, emptyList())
        val edges = store.edges()
        val incoming = edges.filter { it.toId == id }
        val outgoing = edges.filter { it.fromId == id }
        val affectedPaths = profile.all().values
            .filter { id in it.evidenceMemoryIds }.map { it.path }
        return ForgetResult(listOf(id), incoming.size * outgoing.size, affectedPaths)
    }

    /** Consolidation purge: physically drop soft-deleted memories older than [cutoffMs]. */
    fun purgeSoftDeleted(cutoffMs: Long, nowMs: Long): List<String> {
        val victims = store.memories().values.filter { it.softDeletedAt != null && it.softDeletedAt!! < cutoffMs }
        victims.forEach { forgetOne(it.id, nowMs) }
        return victims.map { it.id }
    }

    /** The inverse of purgeSoftDeleted: re-activates a memory before its grace period elapses.
     *  Returns false for an unknown id, a memory that was never soft-deleted, or one that's
     *  already been physically purged -- the last two are indistinguishable, correctly, since
     *  the memory is equally unrecoverable either way from restore's point of view. Also refuses
     *  a memory that's superseded (Memory.active requires supersededBy == null too) -- clearing
     *  softDeletedAt alone can't make it active again, so claiming success would be misleading. */
    fun restore(id: String): Boolean {
        val m = store.memories()[id] ?: return false
        if (m.softDeletedAt == null) return false
        if (m.supersededBy != null) return false
        store.upsertMemory(m.copy(softDeletedAt = null))
        return true
    }
}
