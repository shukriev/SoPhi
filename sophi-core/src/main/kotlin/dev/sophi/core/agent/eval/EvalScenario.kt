package dev.sophi.core.agent.eval

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.plan.PlanOutcome
import dev.sophi.core.agent.plan.PlanRunnerConfig
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.agent.plan.buildPlanRunner
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry

/**
 * A before/after evaluation probe with a real external referent: [check] is a real shell command,
 * not the model's own self-judgment. [PlanOutcome.finalStatus] reports Met/Exhausted directly from
 * that command's exit code — this wrapper adds no separate scoring logic.
 */
data class EvalScenario(
    val name: String,
    val goalPrompt: String,
    val check: StopCondition.ShellCheck,
    val maxIterations: Int = 5
)

suspend fun runEvalScenario(
    provider: LLMProvider,
    registry: ToolRegistry,
    sessionManager: SessionManager,
    contextWindowTokens: Int,
    model: String,
    scenario: EvalScenario
): PlanOutcome {
    val runner = buildPlanRunner(
        provider = provider,
        registry = registry,
        sessionManager = sessionManager,
        config = PlanRunnerConfig(model = model, maxStepExecutions = scenario.maxIterations),
        contextWindowTokens = contextWindowTokens
    )
    val sessionId = sessionManager.create(title = "eval:${scenario.name}").id
    return runner.run(sessionId, scenario.goalPrompt, scenario.check)
}
