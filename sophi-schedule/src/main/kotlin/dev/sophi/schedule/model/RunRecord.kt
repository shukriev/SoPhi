package dev.sophi.schedule.model

import kotlinx.serialization.Serializable

@Serializable
data class RunRecord(
    val taskId: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val outcome: RunOutcome,
    val summary: String
)
