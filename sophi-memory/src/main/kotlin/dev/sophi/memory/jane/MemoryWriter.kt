package dev.sophi.memory.jane

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.memory.TurnObservation
import java.util.UUID
import kotlin.math.min

/**
 * Encoding pipeline (spec §7): verdict → redaction → embedding → system-side signals
 * (novelty/repetition/recency) → α blend → θ gate → dedupe-merge → supersede → edges → profile.
 */
class MemoryWriter(
    private val store: PalaceStore,
    private val profile: UserProfile,
    private val embeddings: EmbeddingProvider,
    private val embeddingModelName: String,
    private val config: JanesPalaceConfig
) {
    internal suspend fun write(turn: TurnObservation, verdict: EncoderVerdict): List<Memory> {
        val stored = mutableListOf<Memory>()
        val all = store.memories()

        for (vm in verdict.memories) {
            val room = runCatching { Room.valueOf(vm.room) }.getOrNull() ?: continue
            val text = redact(vm.text)
            val vector = embeddings.embed(listOf(text)).first()

            val roomMemories = all.values.filter { it.room == room && it.active }
            val similarities = roomMemories.associate { m ->
                m.id to (store.vectorFor(m.id)?.let { cosine(vector, it) } ?: 0.0)
            }
            val maxSim = similarities.values.maxOrNull() ?: 0.0

            // Dedupe-merge before anything else (spec §7).
            if (maxSim >= config.mergeThreshold) {
                val existingId = similarities.entries.maxByOrNull { it.value }!!.key
                val existing = all.getValue(existingId)
                store.upsertMemory(existing.copy(
                    salience = min(1.0, maxOf(existing.salience, blend(vm, nov = 0.0, rep = 1.0)) + 0.05),
                    reinforcedAt = turn.nowMs
                ))
                continue
            }

            val nov = 1.0 - maxSim
            val recent = roomMemories.sortedByDescending { it.createdAt }.take(config.recentWindow)
            val repCount = recent.count { (similarities[it.id] ?: 0.0) >= config.repetitionThreshold }
            val rep = min(1.0, repCount / 3.0)
            val alpha = blend(vm, nov = nov, rep = rep)
            if (alpha < config.significanceThreshold) continue

            val memory = Memory(
                id = "mem_" + UUID.randomUUID(),
                text = text,
                room = room,
                salience = alpha,
                signals = SalienceSignals(rep, vm.emph.coerceIn(0.0, 1.0), nov, vm.aff.coerceIn(0.0, 1.0), 1.0),
                sensitivity = runCatching { Sensitivity.valueOf(vm.sensitivity) }.getOrDefault(Sensitivity.PERSONAL),
                provenance = runCatching { Provenance.valueOf(vm.provenance) }.getOrDefault(Provenance.USER_DIRECT),
                createdAt = turn.nowMs,
                reinforcedAt = turn.nowMs,
                sourceSessionId = turn.sessionId
            )
            store.upsertMemory(memory)
            store.putEmbedding(memory.id, embeddingModelName, vector)
            stored += memory

            // Causal links: only to ids that exist (spec §7 — the encoder may only cite the shortlist).
            vm.causedBy.filter { it in all || stored.any { s -> s.id == it } }.forEach { causeId ->
                store.upsertEdge(CausalEdge(causeId, memory.id, vm.thread ?: "thread-${memory.id.takeLast(8)}"))
            }

            // Correction absorption: supersede + reroute edges (spec §7).
            vm.supersedes?.let { oldId ->
                all[oldId]?.let { old ->
                    store.upsertMemory(old.copy(supersededBy = memory.id))
                    store.edges().filter { it.toId == oldId }.forEach {
                        store.upsertEdge(it.copy(removed = true))
                        store.upsertEdge(it.copy(toId = memory.id, removed = false))
                    }
                    store.edges().filter { it.fromId == oldId }.forEach {
                        store.upsertEdge(it.copy(removed = true))
                        store.upsertEdge(it.copy(fromId = memory.id, removed = false))
                    }
                }
            }
        }

        verdict.profile.forEach { pe ->
            val evidenceId = stored.lastOrNull()?.id ?: "turn_${turn.sessionId}_${turn.nowMs}"
            // An explicit "remember this" starts above the recall floor (spec §6's 0.7) immediately;
            // a merely-mentioned fact still needs corroboration before it's asserted back confidently.
            val startConfidence = if (pe.explicit) 0.8 else 0.5
            profile.observeEvidence(pe.path, pe.value, evidenceId, turn.nowMs, startConfidence)
        }
        return stored
    }

    private fun blend(vm: VerdictMemory, nov: Double, rep: Double): Double =
        config.wRep * rep + config.wEmph * vm.emph.coerceIn(0.0, 1.0) + config.wNov * nov +
            config.wAff * vm.aff.coerceIn(0.0, 1.0) + config.wRec * 1.0
}
