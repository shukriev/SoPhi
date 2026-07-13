package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.providers.buildClaudeProvider
import dev.sophi.ai.providers.buildOpenAiCompatProvider
import java.time.Duration

internal fun buildProvider(
    providerType: String,
    apiKeyOverride: String?,
    baseUrl: String?,
    model: String,
    requestTimeoutSeconds: Long = 60,
    maxRetries: Int = 2
): LLMProvider = when (providerType.lowercase()) {
    "claude" -> {
        val apiKey = apiKeyOverride ?: System.getenv("ANTHROPIC_API_KEY")
            ?: throw UsageError("ANTHROPIC_API_KEY environment variable is not set")
        buildClaudeProvider(apiKey, model)
    }
    "openai-compat" -> {
        val url = baseUrl
            ?: throw UsageError("--base-url is required when --provider openai-compat is selected")
        buildOpenAiCompatProvider(
            url, apiKeyOverride, model, name = "openai-compat",
            requestTimeout = Duration.ofSeconds(requestTimeoutSeconds), maxRetries = maxRetries)
    }
    else -> throw UsageError("Unknown provider: $providerType (expected 'claude' or 'openai-compat')")
}
