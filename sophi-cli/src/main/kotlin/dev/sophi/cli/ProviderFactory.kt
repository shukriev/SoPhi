package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.providers.ProviderConfigException
import dev.sophi.ai.providers.buildProviderFromType
import java.time.Duration

internal fun buildProvider(
    providerType: String,
    apiKeyOverride: String?,
    baseUrl: String?,
    model: String,
    requestTimeoutSeconds: Long = 60,
    maxRetries: Int = 2
): LLMProvider = try {
    buildProviderFromType(
        providerType, apiKeyOverride, baseUrl, model,
        requestTimeout = Duration.ofSeconds(requestTimeoutSeconds), maxRetries = maxRetries,
        missingApiKeyMessage = "ANTHROPIC_API_KEY environment variable is not set",
        missingBaseUrlMessage = "--base-url is required when --provider openai-compat is selected",
        unknownTypeMessage = "Unknown provider: $providerType (expected 'claude' or 'openai-compat')"
    )
} catch (e: ProviderConfigException) {
    throw UsageError(e.message ?: "Invalid provider configuration")
}
