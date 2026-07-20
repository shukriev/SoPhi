package dev.sophi.cli.streaming

object StreamingIndicator {
    private val animationFrames = listOf(
        "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    )

    fun getAnimationFrames(): List<String> = animationFrames

    fun renderSpinner(phase: StreamingPhase, frameIndex: Int = 0): String {
        return when (phase) {
            is StreamingPhase.Generating -> {
                val frame = animationFrames[frameIndex % animationFrames.size]
                val elapsed = String.format("%.1f", phase.elapsedSeconds())
                val totalTokens = phase.tokenCount
                "$frame Generating... ($totalTokens tokens, ${elapsed}s)"
            }
            is StreamingPhase.ExecutingTool -> {
                val frame = animationFrames[frameIndex % animationFrames.size]
                val elapsed = String.format("%.1f", phase.elapsedSeconds())
                "🔧 Calling ${phase.toolName}... (${elapsed}s)"
            }
        }
    }

    fun renderError(phase: StreamingPhase, tokenCount: Int): String {
        val elapsed = String.format("%.1f", phase.elapsedSeconds())
        return "❌ Generation failed ($tokenCount tokens received, ${elapsed}s)"
    }
}
