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
    val compressAgeMs: Long = 90 * DAY,
    val compressPriorityCeiling: Double = 0.1,
    val pruneFloor: Double = 0.02,
    val softDeleteGraceMs: Long = 30 * DAY,
    val consolidationIntervalMs: Long = 24 * HOUR,
    val recallTimeoutMs: Long = 2_000,
    val encoderModel: String? = null,
    val sessionModel: String? = null,
    val encoderMaxTokens: Int = 1024
)
