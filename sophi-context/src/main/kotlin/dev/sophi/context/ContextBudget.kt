package dev.sophi.context

/** Character budgets for the context engine's prompt contributions (chars = tokens × 4, spec §6.7). */
data class ContextBudget(val windowTokens: Int) {
    val orientationTokens: Int = (windowTokens * 0.08).toInt().coerceIn(600, 4_000)
    val hintTokens: Int = minOf((windowTokens * 0.01).toInt(), 300)
    val orientationChars: Int get() = orientationTokens * 4
    val hintChars: Int get() = hintTokens * 4
}
