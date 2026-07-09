package dev.sophi.learning

class ToolReliabilitySection(
    private val stats: ToolStatsStore,
    private val config: LearningConfig
) {
    fun render(scope: String): String? {
        val problems = stats.stats(scope).filterValues { s ->
            s.attempts >= config.reliabilityMinAttempts &&
                (s.failures.toDouble() / s.attempts >= config.reliabilityFailureRate || s.streak >= 3)
        }
        if (problems.isEmpty()) return null
        return buildString {
            appendLine("## Tool reliability notes (this project)")
            problems.forEach { (tool, s) ->
                append("- $tool: failed ${s.failures} of last ${s.attempts} calls.")
                s.lastErrors.lastOrNull()?.let { append(" Last error: \"$it\".") }
                appendLine()
            }
        }.trimEnd()
    }
}
