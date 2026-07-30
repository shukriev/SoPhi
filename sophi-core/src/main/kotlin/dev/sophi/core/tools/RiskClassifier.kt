package dev.sophi.core.tools

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface RiskClassifier {
    suspend fun classify(
        toolName: String,
        toolDescription: String,
        tier: RiskLevel,
        argumentsJson: String
    ): RuleVerdict
}

/**
 * Fallback classifier for auto mode: a single non-streaming completion, not part of the main
 * conversation. Any failure (timeout, provider error, unparseable response) fails safe to
 * HIGH_RISK rather than guessing — the caller then falls back to a human prompt.
 */
class LlmRiskClassifier(
    private val provider: LLMProvider,
    private val model: String,
    private val timeout: Duration = 5.seconds
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
                maxTokens = 8,
                temperature = 0.0
            )
            when (val response = provider.complete(request)) {
                is LLMResponse.Text -> parseVerdict(response.content)
                else -> RuleVerdict.HIGH_RISK
            }
        }
    }.getOrElse { RuleVerdict.HIGH_RISK }

    private fun parseVerdict(content: String): RuleVerdict =
        when (content.trim().uppercase()) {
            "LOW_RISK" -> RuleVerdict.LOW_RISK
            else -> RuleVerdict.HIGH_RISK
        }
}
