package dev.sophi.cli.streaming

import java.time.Instant

sealed class StreamingPhase(open val startTime: Instant = Instant.now()) {
    data class Generating(
        val tokenCount: Int = 0,
        override val startTime: Instant = Instant.now()
    ) : StreamingPhase(startTime)

    data class ExecutingTool(
        val toolName: String,
        override val startTime: Instant = Instant.now()
    ) : StreamingPhase(startTime)
}

fun StreamingPhase.elapsedSeconds(): Double {
    val elapsed = java.time.Duration.between(startTime, Instant.now())
    return elapsed.toMillis() / 1000.0
}
