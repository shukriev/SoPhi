package dev.sophi.cli.streaming

object ThinkingTokenDetector {
    private var inThinkingBlock = false

    fun parseThinkingBlock(token: String): Pair<Boolean, String> {
        var cleanToken = token

        if (token.contains("<thinking>")) {
            inThinkingBlock = true
            cleanToken = token.replace("<thinking>", "")
        }

        if (token.contains("</thinking>")) {
            inThinkingBlock = false
            cleanToken = token.replace("</thinking>", "")
        }

        return inThinkingBlock to cleanToken
    }

    fun reset() {
        inThinkingBlock = false
    }
}
