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
 */
class Consolidator(
    private val store: PalaceStore,
    private val forgetEngine: ForgetEngine,
    private val provider: LLMProvider?,
    private val config: JanesPalaceConfig
) {
    fun isDue(nowMs: Long): Boolean =
        (store.lastConsolidationMs() ?: Long.MIN_VALUE) + config.consolidationIntervalMs <= nowMs

    suspend fun run(nowMs: Long): ConsolidationReport {
        val merged = merge(nowMs)
        val strengthened = strengthen(nowMs)
        val compressed = compress(nowMs)
        val pruned = prune(nowMs)
        val purged = forgetEngine.purgeSoftDeleted(nowMs - config.softDeleteGraceMs, nowMs)
        store.markConsolidation(nowMs)
        return ConsolidationReport(merged, strengthened, compressed, pruned, purged)
    }

    private fun merge(nowMs: Long): Int {
        var count = 0
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
                        absorbed += b.id; count++
                    }
                }
            }
        }
        return count
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

    private suspend fun compress(nowMs: Long): Int {
        val llm = provider ?: return 0
        val model = config.encoderModel ?: config.sessionModel ?: return 0
        val all = store.memories()
        val edges = store.edges().filter { !it.compressed }
        var count = 0
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
            members.drop(1).dropLast(1).forEach { store.upsertMemory(it.copy(softDeletedAt = nowMs)) }
            count++
        }
        return count
    }

    private fun prune(nowMs: Long): Int {
        val linked = store.edges().flatMap { listOf(it.fromId, it.toId) }.toSet()
        return store.memories().values
            .filter { it.active && it.id !in linked && priority(it, nowMs, config.halfLifeMs) < config.pruneFloor }
            .onEach { store.upsertMemory(it.copy(softDeletedAt = nowMs)) }
            .count()
    }
}
