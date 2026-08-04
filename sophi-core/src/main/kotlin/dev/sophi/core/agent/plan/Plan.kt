package dev.sophi.core.agent.plan

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Plan(
    val id: String,
    val goalPrompt: String,
    val steps: List<PlanStep>,
    val version: Int = 1,
    val parentPlanId: String? = null,
    /** Tree edge: the step in the parent plan that this plan decomposes. Null on a root plan. */
    val parentStepId: String? = null,
    /** 0 on a root plan; parent depth + 1 on a sub-plan. */
    val depth: Int = 0
) {
    companion object {
        fun newId(): String = "plan_" + UUID.randomUUID()
    }
}

@Serializable
data class PlanStep(
    val id: String,
    val instruction: String,
    val dependsOn: List<String> = emptyList(),
    val status: StepStatus = StepStatus.Pending,
    val confidence: Double? = null,
    val modelOverride: String? = null,
    /** Set by the planner when this step is a multi-step project in its own right. */
    val decompose: Boolean = false,
    /** Set once this step has been expanded into a sub-plan, by either trigger. */
    val childPlanId: String? = null
)

@Serializable
enum class StepStatus { Pending, Running, Done, Failed }

@Serializable
sealed class StopCondition {
    @Serializable
    object LlmJudged : StopCondition()

    @Serializable
    data class ShellCheck(val command: String, val expectExitZero: Boolean = true) : StopCondition()
}

enum class PlanFinalStatus { Met, Exhausted }

data class ReplanEvent(val stepId: String, val reason: String, val atVersion: Int)

enum class DecompositionTrigger { Declared, Failure }

data class DecompositionEvent(
    val stepId: String,
    val childPlanId: String,
    val childStepCount: Int,
    val childStatus: PlanFinalStatus,
    val trigger: DecompositionTrigger
)

data class PlanOutcome(
    val finalStatus: PlanFinalStatus,
    val finalOutput: String,
    val planVersionCount: Int,
    /** Steps in the final ROOT plan version only — sub-plan sizes are reported via [decompositions]. */
    val totalSteps: Int,
    val replans: List<ReplanEvent>,
    val decompositions: List<DecompositionEvent> = emptyList(),
    val planId: String = "",
    /** The final plan version's steps, so callers can render a per-step summary. */
    val finalSteps: List<PlanStep> = emptyList()
)
