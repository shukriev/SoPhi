package dev.sophi.schedule.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ScheduledTask(
    val id: String = "task_" + UUID.randomUUID(),
    val name: String,
    val trigger: Trigger,
    val mode: TaskMode,
    val prompt: String,
    val destructiveToolAllowlist: Set<String> = emptySet(),
    val subagentType: String? = null,
    val enabled: Boolean = true,
    val lastRunAtMs: Long? = null,
    val nextRunAtMs: Long? = null,
    val iterationCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)
