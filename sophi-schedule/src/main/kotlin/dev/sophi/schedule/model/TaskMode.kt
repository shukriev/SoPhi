package dev.sophi.schedule.model

import kotlinx.serialization.Serializable

@Serializable
sealed class TaskMode {
    @Serializable
    object Recurring : TaskMode()

    @Serializable
    data class Goal(val stopCondition: StopCondition, val maxIterations: Int) : TaskMode()
}
