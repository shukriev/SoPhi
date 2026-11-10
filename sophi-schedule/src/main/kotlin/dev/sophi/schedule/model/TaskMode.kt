package dev.sophi.schedule.model

import dev.sophi.core.agent.plan.StopCondition
import kotlinx.serialization.Serializable

@Serializable
sealed class TaskMode {
    @Serializable
    object Recurring : TaskMode()

    @Serializable
    data class Goal(val stopCondition: StopCondition, val maxIterations: Int) : TaskMode()

    /** Fetch [url] each run and speak up only when its text changed; no model call. */
    @Serializable
    data class Watch(val url: String) : TaskMode()
}
