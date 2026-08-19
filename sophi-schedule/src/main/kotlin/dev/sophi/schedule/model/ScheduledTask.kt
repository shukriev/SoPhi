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
    val toolGrants: Set<String> = emptySet(),
    val subagentType: String? = null,
    /**
     * Opt-in cumulative wall-clock cap: if the sum of this task's own run durations within the
     * trailing [wallClockWindowMs] already meets or exceeds this, the next run is skipped
     * (recorded as RunOutcome.Failed) before any session is created or provider called. Null
     * (the default) means no budget — every existing task is unaffected.
     */
    val maxWallClockMsPerWindow: Long? = null,
    /** The trailing window [maxWallClockMsPerWindow] is measured over. Ignored when that's null. */
    val wallClockWindowMs: Long = 86_400_000L,
    val enabled: Boolean = true,
    val lastRunAtMs: Long? = null,
    val nextRunAtMs: Long? = null,
    val iterationCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)
