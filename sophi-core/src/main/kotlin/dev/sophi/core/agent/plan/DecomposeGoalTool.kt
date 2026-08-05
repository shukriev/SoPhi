package dev.sophi.core.agent.plan

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val DECOMPOSE_GOAL_TOOL_NAME = "decompose_goal"

@Serializable
private data class DecomposeGoalArgs(
    val goal: String,
    @SerialName("expected_tools") val expectedTools: List<String>? = null
)

/**
 * Model-invoked entry point to the planner. Mirrors SubagentTool: constructor-injected
 * collaborators, a depth guard that re-registers a deeper copy of itself, and a risk tier
 * derived from the tools the caller says it expects. Every tool call inside the plan still
 * passes the injected ConfirmationPolicy, so ADR-016's gate is untouched.
 */
class DecomposeGoalTool(
    private val provider: LLMProvider,
    private val fullRegistry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val parentSessionId: String,
    private val parentConfig: AgentConfig,
    private val planLog: PlanLog? = null,
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
    private val depth: Int = 0,
    private val maxToolDepth: Int = 1
) : Tool {

    override val name = DECOMPOSE_GOAL_TOOL_NAME
    override val description =
        "Break a large, multi-step goal into an explicit plan and execute it end to end, " +
            "replanning around steps that fail. Use for goals that need several rounds of work; " +
            "a single action does not need this."
    override val parametersJson = """
        {"type":"object","properties":{"goal":{"type":"string","description":"The goal to decompose and carry out"},"expected_tools":{"type":"array","items":{"type":"string"},"description":"Tool names you expect the plan's steps to need, if known"}},"required":["goal"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override fun riskLevel(argumentsJson: String): RiskLevel {
        val args = runCatching { json.decodeFromString(DecomposeGoalArgs.serializer(), argumentsJson) }.getOrNull()
            ?: return RiskLevel.SAFE
        val tiers = args.expectedTools.orEmpty().mapNotNull { fullRegistry.getOrNull(it)?.riskLevel("{}") }
        return when {
            RiskLevel.DESTRUCTIVE in tiers -> RiskLevel.DESTRUCTIVE
            RiskLevel.CAUTION in tiers -> RiskLevel.CAUTION
            else -> RiskLevel.SAFE
        }
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(DecomposeGoalArgs.serializer(), argumentsJson)

        val stepRegistry = fullRegistry.subset(fullRegistry.names().filter { it != name })
        if (depth + 1 < maxToolDepth) {
            stepRegistry.register(
                DecomposeGoalTool(
                    provider = provider,
                    fullRegistry = fullRegistry,
                    sessionManager = sessionManager,
                    parentSessionId = parentSessionId,
                    parentConfig = parentConfig,
                    planLog = planLog,
                    confirmationPolicy = confirmationPolicy,
                    depth = depth + 1,
                    maxToolDepth = maxToolDepth
                )
            )
        }

        val runner = buildPlanRunner(
            provider = provider,
            registry = stepRegistry,
            sessionManager = sessionManager,
            config = PlanRunnerConfig(
                model = parentConfig.model,
                maxTokens = parentConfig.maxTokens,
                allowParallelSteps = false
            ),
            // TODO(next task): threaded from the caller instead of assumed.
            contextWindowTokens = 200_000,
            confirmationPolicy = confirmationPolicy,
            grants = args.expectedTools?.toSet() ?: emptySet(),
            planLog = planLog
        )
        val outcome = runner.run(parentSessionId, args.goal, StopCondition.LlmJudged)
        val summary = renderOutcome(outcome)
        return if (outcome.finalStatus == PlanFinalStatus.Met) summary
        else "Error: goal not met - $summary"
    }

    private fun renderOutcome(outcome: PlanOutcome): String = buildString {
        appendLine("plan ${outcome.planId} (${outcome.finalStatus}, ${outcome.totalSteps} steps, " +
            "${outcome.replans.size} replans, ${outcome.decompositions.size} sub-plans)")
        outcome.decompositions.forEach {
            appendLine("[${it.stepId}] expanded into ${it.childPlanId} (${it.childStepCount} steps, ${it.childStatus})")
        }
        outcome.finalSteps.forEach {
            appendLine("[${it.id}] ${it.status}${it.confidence?.let { c -> " ($c)" } ?: ""} — ${it.instruction}")
        }
        append(outcome.finalOutput)
    }
}
