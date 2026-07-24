package dev.sophi.cli.streaming

import java.util.Locale

object TokenStreamFormatter {
    fun formatTokenCount(phase: StreamingPhase): String {
        val elapsed = String.format(Locale.ROOT, "%.1f", phase.elapsedSeconds())
        return when (phase) {
            is StreamingPhase.Generating ->
                "(${phase.tokenCount + phase.reasoningTokenCount} tokens, ${elapsed}s)"
            is StreamingPhase.ExecutingTool -> "(${elapsed}s)"
        }
    }

    /** Renders the reasoning buffer (dimmed) ahead of the content buffer, alongside a live token/latency footer. */
    fun renderTokenStream(phase: StreamingPhase, reasoningText: String, contentText: String): String {
        return when (phase) {
            is StreamingPhase.Generating -> buildString {
                if (reasoningText.isNotEmpty()) {
                    append(com.github.ajalt.mordant.rendering.TextStyles.dim(reasoningText))
                    append("\n")
                }
                append(contentText)
                append("\n")
                append(formatTokenCount(phase))
            }
            is StreamingPhase.ExecutingTool -> "🔧 Calling ${phase.toolName}... ${formatTokenCount(phase)}"
        }
    }
}
