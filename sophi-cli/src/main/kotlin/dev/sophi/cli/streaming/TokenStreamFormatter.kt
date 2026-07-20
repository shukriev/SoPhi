package dev.sophi.cli.streaming

object TokenStreamFormatter {
    fun formatToken(token: String, isThinking: Boolean = false): String {
        return if (isThinking) {
            "[thinking] $token"
        } else {
            "[response] $token"
        }
    }

    fun formatTokenCount(phase: StreamingPhase): String {
        return when (phase) {
            is StreamingPhase.Generating -> {
                val total = phase.tokenCount
                val elapsed = String.format("%.1f", phase.elapsedSeconds())
                "($total tokens, ${elapsed}s)"
            }
            is StreamingPhase.ExecutingTool -> {
                val elapsed = String.format("%.1f", phase.elapsedSeconds())
                "(${elapsed}s)"
            }
        }
    }

    fun renderTokenStream(phase: StreamingPhase): String {
        return when (phase) {
            is StreamingPhase.Generating -> {
                val thinkingLines = phase.thinkingTokens.map { "[thinking] $it" }
                val responseLines = phase.responseTokens.map { "[response] $it" }
                val combined = (thinkingLines + responseLines).joinToString("")
                "$combined\n${formatTokenCount(phase)}"
            }
            is StreamingPhase.ExecutingTool -> {
                "🔧 Calling ${phase.toolName}... ${formatTokenCount(phase)}"
            }
        }
    }
}
