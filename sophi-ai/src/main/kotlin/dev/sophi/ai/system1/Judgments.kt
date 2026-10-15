package dev.sophi.ai.system1

/** The tags judgment call sites set on [dev.sophi.ai.api.CompletionRequest.judgment] (ADR-042). */
object Judgments {
    const val RISK = "risk"
    const val SKILL_SECURITY = "skill-security"
    const val STEP_CRITIC = "step-critic"
    const val PLAN_CRITIC = "plan-critic"
    const val GOAL_JUDGE = "goal-judge"
    const val EVALUATOR = "evaluator"
    const val ENCODER = "encoder"
    const val CONSOLIDATOR_PATTERNS = "consolidator-patterns"
    const val WORKFLOW_REFLECTOR = "workflow-reflector"
    const val COMPACTOR = "compactor"
    const val LOOP_SUMMARISE = "loop-summarise"
    const val CONSOLIDATOR_CHAIN_SUMMARY = "consolidator-chain-summary"

    val ALL: Set<String> = setOf(
        RISK, SKILL_SECURITY, STEP_CRITIC, PLAN_CRITIC, GOAL_JUDGE, EVALUATOR, ENCODER,
        CONSOLIDATOR_PATTERNS, WORKFLOW_REFLECTOR, COMPACTOR, LOOP_SUMMARISE, CONSOLIDATOR_CHAIN_SUMMARY
    )

    /** A "safe" verdict on these must come from System 2, so they can never be SYSTEM1. */
    val SAFETY: Set<String> = setOf(RISK, SKILL_SECURITY)
}
