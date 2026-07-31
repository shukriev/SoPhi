package dev.sophi.core.agent.plan

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Plan(
    val id: String,
    val goalPrompt: String,
    val steps: List<PlanStep>,
    val version: Int = 1,
    val parentPlanId: String? = null
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
    val modelOverride: String? = null
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

data class PlanOutcome(
    val finalStatus: PlanFinalStatus,
    val finalOutput: String,
    val planVersionCount: Int,
    val totalSteps: Int,
    val replans: List<ReplanEvent>
)
