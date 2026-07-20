package dev.sophi.cli.streaming

import java.util.Locale

object TokenStreamFormatter {
    fun formatTokenCount(phase: StreamingPhase): String {
        val elapsed = String.format(Locale.ROOT, "%.1f", phase.elapsedSeconds())
        return when (phase) {
            is StreamingPhase.Generating -> "(${phase.tokenCount} tokens, ${elapsed}s)"
            is StreamingPhase.ExecutingTool -> "(${elapsed}s)"
        }
    }

    /** Renders the raw text streamed so far alongside a live token/latency footer. */
    fun renderTokenStream(phase: StreamingPhase, text: String): String {
        return when (phase) {
            is StreamingPhase.Generating -> "$text\n${formatTokenCount(phase)}"
            is StreamingPhase.ExecutingTool -> "🔧 Calling ${phase.toolName}... ${formatTokenCount(phase)}"
        }
    }
}
