package dev.sophi.schedule.model

import kotlinx.serialization.Serializable

@Serializable
data class RunRecord(
    val taskId: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val outcome: RunOutcome,
    val summary: String,
    /**
     * How many times the run replanned, or null when the run had no plan at all (a Recurring
     * task, or a goal run that died before producing an outcome). Null and 0 mean different
     * things and must stay distinguishable: 0 says "a plan ran and never needed replanning",
     * which is the reading a probation review of TreePlanner's replan search depends on.
     */
    val replans: Int? = null,
    /**
     * How many steps were expanded into sub-plans (ADR-020), same null convention. Paired with
     * [replans] because a failed step decomposes BEFORE it replans, so the two counts together
     * are what separate "the search ran" from "decomposition intercepted the failure".
     */
    val decompositions: Int? = null
)
