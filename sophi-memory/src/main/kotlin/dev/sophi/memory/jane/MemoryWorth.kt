package dev.sophi.memory.jane

enum class WorthClass { HIGH, LOW, LOW_EVIDENCE, MIXED }

/**
 * Classifies a memory's outcome-attribution record (spec: "Memory Worth"). [WorthClass.LOW_EVIDENCE]
 * takes priority over the ratio itself -- a memory recalled twice, both times in a successful
 * session, is not "100% high value," it's "too little evidence to say anything." Correlation, not
 * causation: a memory riding along genuinely useful neighbors in the same session accrues credit
 * it didn't individually earn, which is exactly why [worthMultiplier] stays neutral outside a
 * clearly one-sided, well-evidenced ratio rather than reacting to every ratio it sees.
 */
fun worthClass(m: Memory, config: JanesPalaceConfig): WorthClass {
    val total = m.hitsPositive + m.hitsNegative
    if (total < config.worthMinEvidence) return WorthClass.LOW_EVIDENCE
    val ratio = m.hitsPositive.toDouble() / total
    return when {
        ratio > config.worthHighThreshold -> WorthClass.HIGH
        ratio < config.worthLowThreshold -> WorthClass.LOW
        else -> WorthClass.MIXED
    }
}

/**
 * >1 boosts retrieval score / lowers the effective prune floor (harder to prune); <1 does the
 * opposite; exactly 1.0 (LOW_EVIDENCE or MIXED) changes nothing -- deliberately a no-op, not just
 * a weak nudge, so an unproven or genuinely ambiguous memory is judged purely on decay as before.
 */
fun worthMultiplier(m: Memory, config: JanesPalaceConfig): Double = when (worthClass(m, config)) {
    WorthClass.HIGH -> config.worthBoostMultiplier
    WorthClass.LOW -> config.worthSuppressMultiplier
    WorthClass.LOW_EVIDENCE, WorthClass.MIXED -> 1.0
}

/**
 * Attributes [success] to every memory recalled during [sessionId] (per [PalaceStore.logRecall],
 * already written by [PalaceWalker] at retrieval time -- no new tracking needed to know which
 * memories a session saw). A memory recalled twice in the same session earns two hits, not one:
 * [worthClass]'s evidence floor is about total attributed hits, and a memory that keeps getting
 * pulled into successful sessions should accumulate evidence for that faster than one that's
 * barely ever recalled.
 */
fun applyOutcome(store: PalaceStore, sessionId: String, success: Boolean) {
    store.recallsSince(0L).filter { it.sessionId == sessionId }
        .groupingBy { it.memoryId }.eachCount()
        .forEach { (id, count) ->
            val m = store.memories()[id] ?: return@forEach
            store.upsertMemory(
                if (success) m.copy(hitsPositive = m.hitsPositive + count)
                else m.copy(hitsNegative = m.hitsNegative + count)
            )
        }
}
