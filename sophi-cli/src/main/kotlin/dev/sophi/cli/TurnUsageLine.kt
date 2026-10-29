package dev.sophi.cli

import dev.sophi.core.agent.TurnEvent
import java.util.Locale

/** 12345 → `12.3k`; under 1000 as is. */
private fun tokens(n: Int): String = if (n < 1000) n.toString() else String.format(Locale.ROOT, "%.1fk", n / 1000.0)
private fun usd(v: Double, digits: Int) = String.format(Locale.ROOT, "$%.${digits}f", v)

/**
 * The line after each reply: the prompt size now ("in", the last round's input) and what the turn wrote
 * ("out", summed), then cost on a cloud provider ([turnUsd] null = not in the catalog), or speed when local.
 */
fun turnUsageLine(
    model: String,
    rounds: List<TurnEvent.RoundUsage>,
    cloud: Boolean,
    turnUsd: Double?,
    sessionUsd: Double,
    maxUsd: Double?,
): String {
    if (rounds.isEmpty()) return ""
    val out = rounds.sumOf { it.usage.outputTokens }
    val head = "$model · ${tokens(rounds.last().usage.inputTokens)} in · ${tokens(out)} out"
    if (cloud) {
        turnUsd ?: return "$head · price unknown"
        return "$head · ${usd(turnUsd, 4)} this turn · ${usd(sessionUsd, 2)} session" + (maxUsd?.let { " of ${usd(it, 2)}" } ?: "")
    }
    val millis = rounds.sumOf { it.generationMillis }
    return if (millis <= 0) head else "$head · ${Math.round(out / (millis / 1000.0))} tok/s"
}

const val BUDGET_HINT = "Raise the budget with /settings max-cost-usd <usd>, or 0 for none."
