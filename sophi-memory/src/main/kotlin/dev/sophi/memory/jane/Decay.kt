package dev.sophi.memory.jane

import kotlin.math.pow

/**
 * Decayed retrieval priority (spec §4.2): salience × 2^(−Δt / halfLife), Δt from reinforcedAt.
 * Computed at read time, never stored.
 */
fun priority(m: Memory, nowMs: Long, halfLifeMs: Map<Room, Long>): Double {
    val hl = halfLifeMs.getValue(m.room).toDouble()
    val dt = (nowMs - m.reinforcedAt).coerceAtLeast(0L).toDouble()
    return m.salience * 2.0.pow(-dt / hl)
}
