package dev.sophi.memory.jane

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.memory.MemoryBlock
import dev.sophi.memory.RecallQuery
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private data class Hit(val memory: Memory, val score: Double, val direct: Boolean, val semantic: Double)

/**
 * Recall (spec §6): blended scoring over active rooms, neighborhood + narrative expansion,
 * sensitivity guard, VERIFY markers, structured rendering. No LLM call on this path.
 */
class PalaceWalker(
    private val store: PalaceStore,
    private val index: EmbeddingIndex,
    private val profile: UserProfile,
    private val embeddings: EmbeddingProvider,
    private val config: JanesPalaceConfig
) {
    // Profile-attribute embeddings for the resonance term, invalidated when the profile changes.
    private var profileVectors: Pair<Set<String>, List<FloatArray>>? = null

    suspend fun walk(query: RecallQuery, queryVector: FloatArray, rooms: List<Room>): MemoryBlock? {
        val all = store.memories()
        val candidates = all.values.filter { it.active && it.room in rooms }
        if (candidates.isEmpty()) return null

        val profVecs = profileVectorsFor(profile.view(0.7))
        fun resonance(id: String): Double {
            val v = index.get(id) ?: return 0.0
            return profVecs.maxOfOrNull { cosine(v, it) }?.coerceAtLeast(0.0) ?: 0.0
        }

        // 1. Direct hits: blended score, relevance floor, sensitivity floors (spec §6 steps 3+5).
        val direct = candidates.mapNotNull { m ->
            val sem = index.get(m.id)?.let { cosine(queryVector, it) } ?: return@mapNotNull null
            val floor = when (m.sensitivity) {
                Sensitivity.RESTRICTED -> config.restrictedFloor
                Sensitivity.SENSITIVE -> config.sensitiveFloor
                else -> config.relevanceFloor
            }
            if (sem < floor) return@mapNotNull null
            val score = config.beta1 * sem + config.beta2 * priority(m, query.nowMs, config.halfLifeMs) +
                config.beta3 * resonance(m.id)
            Hit(m, score, direct = true, semantic = sem)
        }.sortedByDescending { it.score }.take(config.directK)
        if (direct.isEmpty()) return null

        // 2. Neighborhood expansion — sensitive tiers never ride along (spec §6 step 5).
        val hits = LinkedHashMap<String, Hit>()
        direct.forEach { hits[it.memory.id] = it }
        direct.forEach { hit ->
            val vec = index.get(hit.memory.id) ?: return@forEach
            index.nearest(vec, config.neighborsPerHit + 1).forEach { n ->
                val m = all[n.id] ?: return@forEach
                if (n.id != hit.memory.id && n.id !in hits && m.active && m.room == hit.memory.room &&
                    m.sensitivity < Sensitivity.SENSITIVE) {
                    hits[n.id] = Hit(m, hit.score * config.neighborWeight, direct = false, semantic = n.score)
                }
            }
        }

        // 3. Narrative expansion: walk causal edges depth-limited in both directions.
        val edges = store.edges()
        val threadLines = mutableListOf<String>()
        direct.forEach { hit ->
            // Sensitive tiers are stripped from the chain itself — never just from `hits` —
            // otherwise a thread line's raw text would still leak a SENSITIVE+ memory that
            // rode along on a causal edge from a non-sensitive direct/indirect hit.
            val chain = walkThread(hit.memory.id, edges, config.narrativeDepth)
                .filter { id -> all[id]?.let { it.active && it.sensitivity < Sensitivity.SENSITIVE } == true }
            if (chain.size > 1) {
                val label = edges.firstOrNull { it.fromId in chain && it.toId in chain }?.threadLabel
                    ?: edges.firstOrNull()?.threadLabel ?: "thread"
                threadLines += "[thread \"$label\"] " + chain.mapNotNull { all[it]?.text }.joinToString(" -> ")
                chain.forEach { id ->
                    val m = all[id] ?: return@forEach
                    if (id !in hits) hits[id] = Hit(m, hit.score * config.narrativeWeight, direct = false, semantic = 0.0)
                }
            }
        }

        val selected = hits.values.sortedByDescending { it.score }.take(config.injectionCap)

        // 4. Audit SENSITIVE+ accesses (spec §6 step 5).
        selected.filter { it.memory.sensitivity >= Sensitivity.SENSITIVE }.forEach { hit ->
            store.appendAudit(buildJsonObject {
                put("ts", JsonPrimitive(query.nowMs)); put("event", JsonPrimitive("sensitive_recall"))
                put("memoryId", JsonPrimitive(hit.memory.id)); put("sessionId", JsonPrimitive(query.sessionId))
            }.toString())
        }
        selected.forEach { store.logRecall(RecallRecord(query.nowMs, it.memory.id, query.sessionId)) }

        val rendered = render(selected, threadLines, query.nowMs)
        store.writeLastRecall(explain(query, selected))
        return MemoryBlock(rendered, selected.map { it.memory.id })
    }

    private suspend fun profileVectorsFor(attrs: List<ProfileAttribute>): List<FloatArray> {
        val keys = attrs.map { "${it.path}=${it.value}" }.toSet()
        profileVectors?.let { (cachedKeys, vecs) -> if (cachedKeys == keys) return vecs }
        val vecs = if (attrs.isEmpty()) emptyList()
            else embeddings.embed(attrs.map { "${it.path} ${it.value}" })
        profileVectors = keys to vecs
        return vecs
    }

    private fun walkThread(startId: String, edges: List<CausalEdge>, depth: Int): List<String> {
        val back = generateSequence(startId) { id -> edges.firstOrNull { it.toId == id }?.fromId }
            .take(depth + 1).toList().reversed()
        val forward = generateSequence(startId) { id -> edges.firstOrNull { it.fromId == id }?.toId }
            .take(depth + 1).toList()
        return (back + forward.drop(1)).distinct()
    }

    private fun render(hits: List<Hit>, threadLines: List<String>, nowMs: Long): String = buildString {
        appendLine("<memory_context>")
        val prof = profile.view(0.7)
        if (prof.isNotEmpty()) {
            appendLine("  <user_profile confidence_floor=\"0.7\">")
            prof.forEach { appendLine("    ${it.path} = ${it.value} (%.2f)".format(it.confidence)) }
            appendLine("  </user_profile>")
        }
        appendLine("  <memories>")
        hits.forEach { hit ->
            val m = hit.memory
            val ageMs = (nowMs - m.reinforcedAt).coerceAtLeast(0)
            val stale = ageMs > config.halfLifeMs.getValue(m.room) / 2
            val verify = stale || m.provenance == Provenance.THIRD_PARTY
            val marker = if (verify) ", VERIFY" else ""
            appendLine("    [${m.room.name.lowercase()}, sal %.2f, %s$marker] %s"
                .format(m.salience, age(ageMs), m.text))
        }
        threadLines.forEach { appendLine("    $it") }
        appendLine("  </memories>")
        append("</memory_context>")
    }

    private fun age(ms: Long): String {
        val d = ms / 86_400_000L
        return when { d >= 1 -> "${d}d ago"; else -> "${(ms / 3_600_000L)}h ago" }
    }

    private fun explain(query: RecallQuery, hits: List<Hit>): String = buildString {
        appendLine("query: ${query.userInput}")
        appendLine("at: ${query.nowMs}  session: ${query.sessionId}")
        hits.forEach { h ->
            appendLine("${h.memory.id} score=%.3f semantic=%.3f direct=%s room=%s sensitivity=%s provenance=%s :: %s"
                .format(h.score, h.semantic, h.direct, h.memory.room, h.memory.sensitivity,
                    h.memory.provenance, h.memory.text))
        }
    }
}
