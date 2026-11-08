package dev.sophi.schedule.model

import kotlinx.serialization.Serializable

@Serializable
sealed class RunOutcome {
    @Serializable
    object Succeeded : RunOutcome()

    @Serializable
    object GoalMet : RunOutcome()

    @Serializable
    object GoalExhausted : RunOutcome()

    @Serializable
    data class Failed(val error: String) : RunOutcome()

    /** Not run at all ([reason]: paused, over budget); never counts toward a failure streak. */
    @Serializable
    data class Skipped(val reason: String) : RunOutcome()
}
