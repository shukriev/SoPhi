package dev.sophi.memory.jane

import java.nio.file.Path

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

data class JanesPalaceConfig(
    val home: Path = Path.of(System.getProperty("user.home"), ".sophi", "memory"),
    val halfLifeMs: Map<Room, Long> = mapOf(
        Room.EPISODES to 72 * HOUR,
        Room.TASKS to 7 * DAY,
        Room.ENTITIES to 90 * DAY,
        Room.KNOWLEDGE to 90 * DAY,
        Room.NARRATIVE to 365 * DAY
    ),
    // Salience weights (spec §7): rep, emph, nov, aff, rec — aff deliberately heaviest.
    val wRep: Double = 0.20, val wEmph: Double = 0.25, val wNov: Double = 0.15,
    val wAff: Double = 0.30, val wRec: Double = 0.10,
    val significanceThreshold: Double = 0.35,
    // Retrieval betas (spec §6): semantic, decayed priority, profile resonance.
    val beta1: Double = 0.45, val beta2: Double = 0.35, val beta3: Double = 0.20,
    val routeTopK: Int = 3,
    val directK: Int = 8,
    val injectionCap: Int = 15,
    val neighborsPerHit: Int = 2,
    val neighborWeight: Double = 0.6,
    val narrativeWeight: Double = 0.4,
    val narrativeDepth: Int = 3,
    val relevanceFloor: Double = 0.25,
    val sensitiveFloor: Double = 0.35,
    val restrictedFloor: Double = 0.55,
    val mergeThreshold: Double = 0.92,
    val repetitionThreshold: Double = 0.80,
    val recentWindow: Int = 20,
    val strengthenRecalls: Int = 2,
    /** Minimum [SalienceSignals.rep] for a memory to be considered for [Consolidator.classifyPatterns]
     *  — repetition evidence a single-turn judgment can't have. 0.5 is the repetition midpoint;
     *  [repetitionThreshold]'s 0.80 is a much stricter bar for outright dedupe-merging. */
    val patternRepThreshold: Double = 0.5,
    /** FIFO cap on [dev.sophi.memory.jane.Memory.occurrences] -- bounds storage for a years-old
     *  daily habit. [dev.sophi.memory.jane.Consolidator.classifyHabits] only needs a representative
     *  recent sample, not every occurrence ever. */
    val habitMaxOccurrencesStored: Int = 200,
    /** Minimum sample size before a time pattern means anything. */
    val habitMinOccurrences: Int = 3,
    /** Fraction of occurrences that must cluster around one hour/day for
     *  [dev.sophi.memory.jane.Consolidator.classifyHabits] to tag a memory as habitual -- a clear
     *  majority, not just a plurality. */
    val habitConcentrationThreshold: Double = 0.6,
    /** How long an unresolved commitment keeps being surfaced/drafted by the daily rescue-style
     *  task before it stops nagging (ADR-035). It stays listable via [JanesPalace.browse] past
     *  this point — expiry only caps the nudge, it never claims the commitment was kept. */
    val commitmentExpiryMs: Long = 30 * DAY,
    val compressAgeMs: Long = 90 * DAY,
    val compressPriorityCeiling: Double = 0.1,
    val pruneFloor: Double = 0.02,
    // Outcome-driven worth (see MemoryWorth.kt): below worthMinEvidence total hits, a memory's
    // hitsPositive/hitsNegative ratio is withheld from judgment regardless of how lopsided it
    // looks -- too little evidence to trust. Boost/suppress multipliers are deliberately
    // asymmetric (1.2 vs 0.5): a bad memory should fade faster than a good one gets amplified.
    val worthMinEvidence: Int = 10,
    val worthHighThreshold: Double = 0.60,
    val worthLowThreshold: Double = 0.40,
    val worthBoostMultiplier: Double = 1.2,
    val worthSuppressMultiplier: Double = 0.5,
    val softDeleteGraceMs: Long = 30 * DAY,
    val consolidationIntervalMs: Long = 24 * HOUR,
    val recallTimeoutMs: Long = 2_000,
    val encoderModel: String? = null,
    val sessionModel: String? = null,
    val encoderMaxTokens: Int = 1024,
    val autoPurgeEnabled: Boolean = true,
    /** Logs every encoder-proposed memory and why it was stored, merged or dropped, so the write
     *  gate's selectivity is measurable at all — [MemoryWriter] is fire-and-forget, so a candidate
     *  the threshold rejects currently leaves no trace anywhere. Off by default: the log holds
     *  candidate text that was deliberately NOT stored, which for an ambient turn can be a
     *  bystander's words. */
    val encoderTelemetry: Boolean = false
) {
    companion object {
        /** Only the literal string "false" disables purging -- unset, empty, or a typo all
         *  preserve today's default-on behavior. Deliberately the inverse of
         *  SOPHI_ORCHESTRATOR_ENABLED's fail-toward-off: that switch guards a brand-new
         *  capability; this one retrofits a switch onto behavior that already runs by default, so
         *  doing nothing must not silently change what every current install already does. */
        fun autoPurgeEnabledFromEnv(env: (String) -> String? = System::getenv): Boolean =
            env("SOPHI_MEMORY_AUTO_PURGE_ENABLED")?.lowercase() != "false"

        /** Fail-toward-off, unlike [autoPurgeEnabledFromEnv]: this is a brand-new capability that
         *  writes un-stored candidate text to disk, so doing nothing must leave it disabled. */
        fun encoderTelemetryFromEnv(env: (String) -> String? = System::getenv): Boolean =
            env("SOPHI_MEMORY_ENCODER_TELEMETRY")?.lowercase() == "true"
    }
}
