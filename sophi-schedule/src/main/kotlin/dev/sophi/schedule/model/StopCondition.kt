package dev.sophi.schedule.model

import kotlinx.serialization.Serializable

@Serializable
sealed class StopCondition {
    @Serializable
    object LlmJudged : StopCondition()

    @Serializable
    data class ShellCheck(val command: String, val expectExitZero: Boolean = true) : StopCondition()
}
