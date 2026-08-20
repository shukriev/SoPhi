package dev.sophi.memory.jane

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.memory.ConsolidationReport
import java.util.UUID
import kotlin.math.min

/**
 * The "sleep" cycle (spec §8): Merge -> Strengthen -> Compress -> Prune -> Purge.
 * No daemon: runs opportunistically (isDue at session end) or on demand from the CLI.
 * Compress needs an LLM; without a provider it is skipped, everything else still runs.
 * Purge is the one physically-irreversible step; config.autoPurgeEnabled can disable just it.
 */
class Consolidator(
    private val store: PalaceStore,
    private val forgetEngine: ForgetEngine,
    private val provider: LLMProvider?,
    private val config: JanesPalaceConfig,
    private val historyStore: ConsolidationHistoryStore
) {
    fun isDue(nowMs: Long): Boolean =
        (store.lastConsolidationMs() ?: Long.MIN_VALUE) + config.consolidationIntervalMs <= nowMs

    suspend fun run(nowMs: Long): ConsolidationReport {
        val merged = merge(nowMs)
        val strengthened = strengthen(nowMs)
        val compressResult = compress(nowMs)
        val pruned = prune(nowMs)
        val purged = if (config.autoPurgeEnabled) forgetEngine.purgeSoftDeleted(nowMs - config.softDeleteGraceMs, nowMs) else emptyList()
        store.markConsolidation(nowMs)
        historyStore.record(ConsolidationRecord(
            ts = nowMs, merged = merged.size, strengthened = strengthened, compressed = compressResult.threadsCompressed,
            pruned = pruned.size, softDeletedIds = merged + compressResult.softDeletedIds + pruned, purgedIds = purged,
            autoPurgeEnabled = config.autoPurgeEnabled
        ))
        return ConsolidationReport(merged.size, strengthened, compressResult.threadsCompressed, pruned.size, purged.size)
    }

    private fun merge(nowMs: Long): List<String> {
        val absorbedAll = mutableListOf<String>()
        Room.entries.forEach { room ->
            val actives = store.memories().values.filter { it.active && it.room == room }
                .sortedBy { it.createdAt }
            val absorbed = mutableSetOf<String>()
            for (i in actives.indices) {
                val a = actives[i]
                if (a.id in absorbed) continue
                var survivorSalience = a.salience
                for (j in i + 1 until actives.size) {
                    val b = actives[j]
                    if (b.id in absorbed) continue
                    val va = store.vectorFor(a.id) ?: continue; val vb = store.vectorFor(b.id) ?: continue
                    if (cosine(va, vb) >= config.mergeThreshold) {
                        survivorSalience = min(1.0, maxOf(survivorSalience, b.salience) + 0.05)
                        store.upsertMemory(a.copy(
                            salience = survivorSalience,
                            reinforcedAt = nowMs))
                        store.upsertMemory(b.copy(softDeletedAt = nowMs))
                        // Absorbed memory's edges move to the survivor.
                        store.edges().filter { it.fromId == b.id || it.toId == b.id }.forEach { e ->
                            store.upsertEdge(e.copy(removed = true))
                            store.upsertEdge(e.copy(
                                fromId = if (e.fromId == b.id) a.id else e.fromId,
                                toId = if (e.toId == b.id) a.id else e.toId, removed = false))
                        }
                        absorbed += b.id
                    }
                }
            }
            absorbedAll += absorbed
        }
        return absorbedAll
    }

    private fun strengthen(nowMs: Long): Int {
        val counts = store.recallsSince(nowMs - 24 * 3_600_000L).groupingBy { it.memoryId }.eachCount()
        val all = store.memories()
        return counts.filter { it.value >= config.strengthenRecalls }.keys.count { id ->
            all[id]?.takeIf { it.active }?.let { m ->
                if (m.reinforcedAt < nowMs) { store.upsertMemory(m.copy(reinforcedAt = nowMs)); true } else false
            } ?: false
        }
    }

    private data class CompressResult(val threadsCompressed: Int, val softDeletedIds: List<String>)

    private suspend fun compress(nowMs: Long): CompressResult {
        val llm = provider ?: return CompressResult(0, emptyList())
        val model = config.encoderModel ?: config.sessionModel ?: return CompressResult(0, emptyList())
        val all = store.memories()
        val edges = store.edges().filter { !it.compressed }
        var threadsCompressed = 0
        val softDeleted = mutableListOf<String>()
        edges.groupBy { it.threadLabel }.forEach { (label, threadEdges) ->
            val ids = (threadEdges.map { it.fromId } + threadEdges.map { it.toId }).distinct()
            val members = ids.mapNotNull { all[it] }.filter { it.active }.sortedBy { it.createdAt }
            if (members.size < 3) return@forEach
            val allOldAndLow = members.all { m ->
                nowMs - m.createdAt > config.compressAgeMs &&
                    priority(m, nowMs, config.halfLifeMs) < config.compressPriorityCeiling
            }
            if (!allOldAndLow) return@forEach

            val summaryText = runCatching {
                when (val r = llm.complete(CompletionRequest(
                    messages = listOf(Message(MessageRole.USER,
                        "Summarize this causal chain in ONE sentence preserving cause and effect:\n" +
                            members.joinToString(" -> ") { it.text })),
                    model = model, maxTokens = 200, temperature = 0.0))) {
                    is LLMResponse.Text -> r.content.trim()
                    else -> null
                }
            }.getOrNull() ?: return@forEach

            val summary = Memory(
                id = "mem_" + UUID.randomUUID(), text = summaryText, room = Room.NARRATIVE,
                salience = members.maxOf { it.salience }, signals = SalienceSignals(0.0, 0.0, 0.0, 0.0, 0.0),
                sensitivity = members.maxBy { it.sensitivity }.sensitivity,
                provenance = Provenance.SYSTEM_INFERRED, createdAt = nowMs, reinforcedAt = nowMs,
                sourceSessionId = "consolidation")
            store.upsertMemory(summary)
            store.vectorFor(members.first().id)?.let { v ->
                store.putEmbedding(summary.id, store.embeddingModel() ?: "", v)
            }
            // Endpoints preserved: first -> summary -> last (spec §4.3); interior soft-deleted.
            store.upsertEdge(CausalEdge(members.first().id, summary.id, label, compressed = true))
            store.upsertEdge(CausalEdge(summary.id, members.last().id, label, compressed = true))
            threadEdges.forEach { store.upsertEdge(it.copy(removed = true)) }
            val interior = members.drop(1).dropLast(1)
            interior.forEach { store.upsertMemory(it.copy(softDeletedAt = nowMs)) }
            softDeleted += interior.map { it.id }
            threadsCompressed++
        }
        return CompressResult(threadsCompressed, softDeleted)
    }

    private fun prune(nowMs: Long): List<String> {
        val linked = store.edges().flatMap { listOf(it.fromId, it.toId) }.toSet()
        return store.memories().values
            .filter { it.active && it.id !in linked && priority(it, nowMs, config.halfLifeMs) < config.pruneFloor }
            .onEach { store.upsertMemory(it.copy(softDeletedAt = nowMs)) }
            .map { it.id }
    }
}
