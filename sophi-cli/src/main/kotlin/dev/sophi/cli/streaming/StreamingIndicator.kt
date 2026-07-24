package dev.sophi.cli.streaming

import java.util.Locale

object StreamingIndicator {
    private val animationFrames = listOf(
        "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    )

    fun getAnimationFrames(): List<String> = animationFrames

    fun renderSpinner(phase: StreamingPhase, frameIndex: Int = 0): String {
        val frame = animationFrames[frameIndex % animationFrames.size]
        val elapsed = String.format(Locale.ROOT, "%.1f", phase.elapsedSeconds())
        return when (phase) {
            is StreamingPhase.Generating ->
                "$frame Generating... (${phase.tokenCount + phase.reasoningTokenCount} tokens, ${elapsed}s)"
            is StreamingPhase.ExecutingTool ->
                "🔧 Calling ${phase.toolName}... (${elapsed}s)"
        }
    }

    fun renderError(phase: StreamingPhase, tokenCount: Int): String {
        val elapsed = String.format(Locale.ROOT, "%.1f", phase.elapsedSeconds())
        return "❌ Generation failed ($tokenCount tokens received, ${elapsed}s)"
    }
}
