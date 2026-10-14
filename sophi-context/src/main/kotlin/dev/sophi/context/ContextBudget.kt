package dev.sophi.context

/** Character budgets for the context engine's prompt contributions (chars = tokens × 4, spec §6.7). */
data class ContextBudget(val windowTokens: Int) {
    val orientationTokens: Int = (windowTokens * 0.08).toInt().coerceIn(600, 4_000)
    val hintTokens: Int = minOf((windowTokens * 0.01).toInt(), 300)
    val orientationChars: Int get() = orientationTokens * 4
    val hintChars: Int get() = hintTokens * 4
}

/** Trim + lowercase a mode/knob value; anything but [default] or [others] falls back to [default]. */
fun normalizeKnob(raw: String?, default: String, vararg others: String): String =
    raw?.trim()?.lowercase()?.takeIf { it == default || it in others } ?: default

/** Repo-derived text goes into the prompt on one line, with no way to open a tag or forge a heading. */
internal fun cleanPromptText(t: String): String =
    t.map { if (it.isISOControl()) ' ' else if (it == '<') '‹' else if (it == '>') '›' else it }.joinToString("").trim()
