package dev.sophi.core.agent.plan

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_CRITIC_MAX_TOKENS = 16
private const val DEFAULT_CRITIC_TIMEOUT_SECONDS = 30L
private const val FAIL_OPEN_CONFIDENCE = 1.0

fun interface StepCritic {
    suspend fun judge(step: PlanStep, agentOutput: String): Double

    companion object {
        /** Always reports full confidence, skipping every escalation check — no LLM call at all. */
        val ALWAYS_FULL_CONFIDENCE = StepCritic { _, _ -> FAIL_OPEN_CONFIDENCE }
    }
}

/**
 * Shaped like LlmRiskClassifier (ADR-017) but fails OPEN, not closed: any timeout, provider
 * error, or malformed response resolves to full confidence rather than zero. This is an
 * efficiency/quality gate deciding whether to spend an extra escalation call, not a safety
 * gate — a missed low-confidence signal only skips an optional escalation, unlike a missed
 * HIGH_RISK verdict which could let a destructive call through unconfirmed.
 */
class LlmStepCritic(
    private val provider: LLMProvider,
    private val model: String,
    private val maxTokens: Int = DEFAULT_CRITIC_MAX_TOKENS,
    private val timeout: Duration = DEFAULT_CRITIC_TIMEOUT_SECONDS.seconds
) : StepCritic {

    override suspend fun judge(step: PlanStep, agentOutput: String): Double = runCatching {
        withTimeout(timeout) {
            val request = CompletionRequest(
                messages = listOf(
                    Message(
                        MessageRole.SYSTEM,
                        "You judge whether an agent's output fully satisfies a single plan step's " +
                            "instruction. Respond with exactly one number between 0.0 and 1.0 — 1.0 means " +
                            "the instruction was fully and correctly satisfied, 0.0 means it was not " +
                            "attempted or clearly failed. No words, no explanation, just the number."
                    ),
                    Message(
                        MessageRole.USER,
                        "Step instruction: ${step.instruction}\n\nAgent output:\n$agentOutput"
                    )
                ),
                model = model,
                maxTokens = maxTokens,
                temperature = 0.0
            )
            when (val response = provider.complete(request)) {
                is LLMResponse.Text -> parseConfidence(response.content)
                else -> FAIL_OPEN_CONFIDENCE
            }
        }
    }.getOrElse { FAIL_OPEN_CONFIDENCE }

    private fun parseConfidence(content: String): Double {
        val match = Regex("""(?:0(?:\.\d+)?|1(?:\.0+)?)""").find(content.trim())
        return match?.value?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: FAIL_OPEN_CONFIDENCE
    }
}
