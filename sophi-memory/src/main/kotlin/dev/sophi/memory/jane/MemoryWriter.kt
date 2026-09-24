package dev.sophi.memory.jane

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.memory.TurnObservation
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import kotlin.math.min

/**
 * Matches a memory whose grammatical subject is the user ("The user is currently interviewing…").
 * Deliberately requires whitespace after "user" so the possessive is untouched: "The user's wife
 * is named Sofia" is a fact about the wife and stays legal for any provenance.
 */
private val USER_AS_SUBJECT = Regex("^\\s*the user\\s", RegexOption.IGNORE_CASE)

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
    /**
     * Records what the encoder proposed and what became of it. Without this, a candidate the
     * significance gate drops is invisible everywhere — the store only ever shows survivors, so
     * "the encoder proposes little" and "the gate rejects nearly everything" look identical from
     * the outside. Never throws: telemetry must not break a write.
     */
    private fun logCandidate(turn: TurnObservation, outcome: String, alpha: Double?, text: String?) {
        if (!config.encoderTelemetry) return
        runCatching {
            store.appendEncoderLog(buildJsonObject {
                put("ts", JsonPrimitive(turn.nowMs))
                put("sessionId", JsonPrimitive(turn.sessionId))
                put("ambient", JsonPrimitive(turn.ambient))
                put("outcome", JsonPrimitive(outcome))
                alpha?.let { put("alpha", JsonPrimitive(it)) }
                text?.let { put("text", JsonPrimitive(it.take(160))) }
            }.toString())
        }
    }

    internal suspend fun write(turn: TurnObservation, verdict: EncoderVerdict): List<Memory> {
        val stored = mutableListOf<Memory>()
        val all = store.memories()

        logCandidate(turn, "proposed_${verdict.memories.size}", null, null)
        for (vm in verdict.memories) {
            val room = runCatching { Room.valueOf(vm.room) }.getOrNull()
                ?: run { logCandidate(turn, "dropped_bad_room", null, null); continue }
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
                logCandidate(turn, "merged", maxSim, text)
                continue
            }

            val nov = 1.0 - maxSim
            val recent = roomMemories.sortedByDescending { it.createdAt }.take(config.recentWindow)
            val repCount = recent.count { (similarities[it.id] ?: 0.0) >= config.repetitionThreshold }
            val rep = min(1.0, repCount / 3.0)
            val alpha = blend(vm, nov = nov, rep = rep)
            if (alpha < config.significanceThreshold) {
                logCandidate(turn, "dropped_below_threshold", alpha, text)
                continue
            }

            // Unstated or unparseable provenance resolves per-turn, in code rather than in the
            // prompt (same reasoning as the isCommitment gate below). Defaulting overheard speech
            // to USER_DIRECT is the worst available guess: it is the one value that unlocks the
            // commitment gate and asserts a stranger's words as the user's own.
            val fallback = if (turn.ambient) Provenance.THIRD_PARTY else Provenance.USER_DIRECT
            val provenance = vm.provenance?.let {
                runCatching { Provenance.valueOf(it) }.getOrDefault(fallback)
            } ?: fallback

            // Provenance says whose fact this is; the sentence has to agree. The encoder reliably
            // tags overheard speech THIRD_PARTY and then still writes "The user ..." as the
            // subject -- metadata right, prose wrong -- so a bystander's career gets stored as the
            // user's own and injected as fact on every later recall. Enforced here rather than in
            // the prompt for the same reason as the isCommitment gate below. Dropping beats
            // storing: a memory known to name the wrong person is worse than no memory at all.
            if (provenance == Provenance.THIRD_PARTY && USER_AS_SUBJECT.containsMatchIn(text)) {
                logCandidate(turn, "dropped_subject_mismatch", alpha, text)
                continue
            }
            val memory = Memory(
                id = "mem_" + UUID.randomUUID(),
                text = text,
                room = room,
                salience = alpha,
                signals = SalienceSignals(rep, vm.emph.coerceIn(0.0, 1.0), nov, vm.aff.coerceIn(0.0, 1.0), 1.0),
                sensitivity = runCatching { Sensitivity.valueOf(vm.sensitivity) }.getOrDefault(Sensitivity.PERSONAL),
                provenance = provenance,
                createdAt = turn.nowMs,
                reinforcedAt = turn.nowMs,
                sourceSessionId = turn.sessionId,
                // Commitment tracking is chat-only in v1 (ADR-035): enforced here in code, not left
                // to the prompt, since provenance can be THIRD_PARTY even outside an ambient turn.
                isCommitment = vm.commitment && provenance == Provenance.USER_DIRECT
            )
            store.upsertMemory(memory)
            store.putEmbedding(memory.id, embeddingModelName, vector)
            stored += memory
            logCandidate(turn, "stored", alpha, text)

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

        // The profile is the *user's* stable traits. An ambient turn cannot establish who was
        // speaking — a guest saying "I'm vegetarian" would otherwise be written as a fact about
        // the user — so ambient turns contribute no profile evidence at all. Enforced here rather
        // than in the prompt, for the same reason as the isCommitment gate above (ADR-035).
        if (turn.ambient) return stored

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
