package dev.sophi.core.tools

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_CLASSIFIER_MAX_TOKENS = 512
private const val DEFAULT_CLASSIFIER_TIMEOUT_SECONDS = 30L

fun interface RiskClassifier {
    suspend fun classify(
        toolName: String,
        toolDescription: String,
        tier: RiskLevel,
        argumentsJson: String
    ): RuleVerdict

    companion object {
        /** God mode: skips the LLM entirely, trusting only the tool's own ruleVerdict() rules. */
        val ALWAYS_LOW_RISK: RiskClassifier = RiskClassifier { _, _, _, _ -> RuleVerdict.LOW_RISK }
    }
}

/**
 * Fallback classifier for auto mode: a single non-streaming completion, not part of the main
 * conversation. Any failure (timeout, provider error, unparseable response) fails safe to
 * HIGH_RISK rather than guessing — the caller then falls back to a human prompt.
 */
class LlmRiskClassifier(
    private val provider: LLMProvider,
    private val model: String,
    private val maxTokens: Int = DEFAULT_CLASSIFIER_MAX_TOKENS,
    private val timeout: Duration = DEFAULT_CLASSIFIER_TIMEOUT_SECONDS.seconds
) : RiskClassifier {

    override suspend fun classify(
        toolName: String,
        toolDescription: String,
        tier: RiskLevel,
        argumentsJson: String
    ): RuleVerdict = runCatching {
        withTimeout(timeout) {
            val request = CompletionRequest(
                messages = listOf(
                    Message(
                        MessageRole.SYSTEM,
                        "You judge whether a single tool call is safe to run without asking a human. " +
                            "Respond with exactly one word: LOW_RISK or HIGH_RISK. LOW_RISK means the " +
                            "call is narrowly scoped and easily reversible even if something goes wrong " +
                            "(e.g. touches only scratch/temporary data). HIGH_RISK means it could cause " +
                            "meaningful, hard-to-reverse damage (e.g. deletes or overwrites something " +
                            "that matters, touches credentials, runs with elevated privileges, or affects " +
                            "systems outside the working directory). When unsure, answer HIGH_RISK."
                    ),
                    Message(
                        MessageRole.USER,
                        "Tool: $toolName\nDescription: $toolDescription\nDeclared risk tier: $tier\n" +
                            "Arguments (JSON): $argumentsJson"
                    )
                ),
                model = model,
                maxTokens = maxTokens,
                temperature = 0.0
            )
            when (val response = provider.complete(request)) {
                is LLMResponse.Text -> parseVerdict(response.content)
                else -> RuleVerdict.HIGH_RISK
            }
        }
    }.getOrElse { RuleVerdict.HIGH_RISK }

    // Reasoning models spend part of maxTokens on hidden chain-of-thought before the visible
    // answer, and even compliant models rarely reply with the bare word requested — so this
    // looks for the verdict as a substring rather than requiring an exact match. HIGH_RISK wins
    // if both markers somehow appear, matching the "when unsure, answer HIGH_RISK" instruction.
    private fun parseVerdict(content: String): RuleVerdict {
        val normalized = content.trim().uppercase()
        return if (normalized.contains("HIGH_RISK")) RuleVerdict.HIGH_RISK
        else if (normalized.contains("LOW_RISK")) RuleVerdict.LOW_RISK
        else RuleVerdict.HIGH_RISK
    }
}
