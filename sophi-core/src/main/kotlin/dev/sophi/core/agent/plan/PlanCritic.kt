package dev.sophi.core.agent.plan

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PLAN_CRITIC_MAX_TOKENS = 16
private const val DEFAULT_PLAN_CRITIC_TIMEOUT_SECONDS = 30L
private const val FAIL_OPEN_SCORE = 1.0

/**
 * Scores a candidate plan that has NOT been executed — the distinction from StepCritic, which
 * judges a finished step against its instruction and therefore needs an agent output that a
 * candidate tail does not have yet.
 */
fun interface PlanCritic {
    suspend fun score(goalPrompt: String, candidate: Plan, failureReason: String): Double
}

/**
 * Shaped like LlmStepCritic (ADR-018) and fails OPEN for the same reason: this is an
 * efficiency/quality gate, not a safety gate. When every candidate fails open they all score
 * 1.0, ties break on order, and the first candidate wins — which is exactly the width-1
 * behavior that existed before this search. The degraded path is the old system, not a broken
 * one.
 *
 * The 0.0-1.0 parse duplicates LlmStepCritic.parseConfidence rather than sharing a helper:
 * this is an experiment that may be deleted outright, and extracting would mean editing
 * working, tested code on its behalf. Revisit only if this graduates.
 */
class LlmPlanCritic(
    private val provider: LLMProvider,
    private val model: String,
    private val maxTokens: Int = DEFAULT_PLAN_CRITIC_MAX_TOKENS,
    private val timeout: Duration = DEFAULT_PLAN_CRITIC_TIMEOUT_SECONDS.seconds
) : PlanCritic {

    override suspend fun score(goalPrompt: String, candidate: Plan, failureReason: String): Double =
        runCatching {
            withTimeout(timeout) {
                val request = CompletionRequest(
                    messages = listOf(
                        Message(
                            MessageRole.SYSTEM,
                            "You judge how likely a proposed sequence of steps is to recover from a " +
                                "failure and achieve the stated goal. Respond with exactly one number " +
                                "between 0.0 and 1.0 — 1.0 means very likely to succeed, 0.0 means it " +
                                "clearly will not work or repeats what already failed. No words, no " +
                                "explanation, just the number."
                        ),
                        Message(MessageRole.USER, buildPrompt(goalPrompt, candidate, failureReason))
                    ),
                    model = model,
                    maxTokens = maxTokens,
                    temperature = 0.0
                )
                when (val response = provider.complete(request)) {
                    is LLMResponse.Text -> parseScore(response.content)
                    else -> FAIL_OPEN_SCORE
                }
            }
        }.getOrElse { FAIL_OPEN_SCORE }

    private fun buildPrompt(goalPrompt: String, candidate: Plan, failureReason: String): String =
        buildString {
            appendLine("## Goal")
            appendLine(goalPrompt)
            appendLine()
            appendLine("## What just went wrong")
            appendLine(failureReason)
            appendLine()
            appendLine("## Proposed recovery steps")
            candidate.steps.forEach { appendLine("- [${it.id}] ${it.instruction}") }
        }

    private fun parseScore(content: String): Double {
        val match = Regex("""(?:0(?:\.\d+)?|1(?:\.0+)?)""").find(content.trim())
        return match?.value?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: FAIL_OPEN_SCORE
    }
}
