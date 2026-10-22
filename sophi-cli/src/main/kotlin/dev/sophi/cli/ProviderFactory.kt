package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.providers.ProviderConfigException
import dev.sophi.ai.providers.buildProviderFromType
import dev.sophi.learning.system1FromHome
import java.nio.file.Path
import java.time.Duration

/** Every CLI command builds its provider here, so all of them get the System-1 tier (ADR-042). */
internal fun buildProvider(
    resolved: ResolvedProvider,
    apiKeyOverride: String?,
    requestTimeoutSeconds: Long = 60,
    maxRetries: Int = 2,
    sophiHome: Path = Path.of(System.getProperty("user.home"), ".sophi")
): LLMProvider {
    val s2 = try {
        buildProviderFromType(
            resolved.type, apiKeyOverride, resolved.baseUrl, resolved.model,
            requestTimeout = Duration.ofSeconds(requestTimeoutSeconds), maxRetries = maxRetries,
            missingApiKeyMessage = "ANTHROPIC_API_KEY environment variable is not set",
            missingBaseUrlMessage = "--base-url is required when --provider ${resolved.type} is selected",
            unknownTypeMessage = "Unknown provider: ${resolved.type} (expected 'local', 'openai-compat' or 'claude')"
        )
    } catch (e: ProviderConfigException) {
        throw UsageError(e.message ?: "Invalid provider configuration")
    }
    return system1FromHome(s2, sophiHome) { System.err.println(it) }
}
