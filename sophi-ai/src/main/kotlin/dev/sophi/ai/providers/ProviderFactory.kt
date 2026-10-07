package dev.sophi.ai.providers

import com.openai.core.Timeout
import dev.sophi.ai.api.LLMProvider
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.setup.OpenAiSetup
import java.time.Duration

fun buildClaudeProvider(apiKey: String, model: String = "claude-3-5-sonnet-20241022"): LLMProvider {
    val options = AnthropicChatOptions.builder()
        .apiKey(apiKey)
        .model(model)
        .maxTokens(4096)
        .build()
    val chatModel = AnthropicChatModel.builder()
        .options(options)
        .build()
    return ClaudeProvider(chatModel)
}

/**
 * Builds an OpenAI-compatible provider pointed at [baseUrl] — works for Ollama
 * (e.g. http://localhost:11434/v1), vLLM (e.g. http://localhost:8000/v1), or any other
 * server implementing the OpenAI chat-completions API. Passing [apiKey] = null puts
 * the underlying client in no-auth mode (OpenAiSetup strips the Authorization header),
 * which is what most local servers expect.
 *
 * [requestTimeout] bounds **silence**, not the whole reply: it is the longest the client waits
 * for the next bytes (connecting, sending, or between streamed chunks). Local reasoning models
 * can take far longer than any fixed limit to finish a reply, and a whole-call limit used to
 * kill such replies mid-stream while tokens were still arriving ("LLM stream error: Stream
 * failed"). The whole call still has a generous ceiling, [MAX_REPLY_DURATION]. Raise
 * [requestTimeout] for models that think silently for a long time before their first token.
 * The OpenAI Java SDK retries [maxRetries] times, each subject to the full [requestTimeout] —
 * effective worst-case latency before a caller sees an error is `requestTimeout * (maxRetries + 1)`.
 * Lower it (e.g. to 0) for a slow model that's consistently near the timeout, so a caller
 * waits requestTimeout once rather than that multiple.
 */
fun buildOpenAiCompatProvider(
    baseUrl: String,
    apiKey: String?,
    model: String,
    name: String = "openai-compat",
    requestTimeout: Duration = Duration.ofSeconds(60),
    maxRetries: Int = 2
): LLMProvider {
    val effectiveApiKey = apiKey ?: ""
    val client = OpenAiSetup.setupSyncClient(
        baseUrl,
        effectiveApiKey,
        null,
        null,
        null,
        null,
        false,
        false,
        model,
        requestTimeout,
        maxRetries,
        null,
        null,
        ObservationRegistry.NOOP,
        null,
        null
    ).withOptions { it.timeout(streamingTimeout(requestTimeout)) }
    val options = OpenAiChatOptions.builder()
        .baseUrl(baseUrl)
        .apiKey(effectiveApiKey)
        .model(model)
        .build()
    val chatModel = OpenAiChatModel.builder()
        .openAiClient(client)
        .options(options)
        .build()
    return OpenAICompatProvider(chatModel, client, name = name)
}

/** Ceiling on one whole call, however actively it streams — only so a server that trickles
 *  bytes forever can't hold a turn open indefinitely. */
internal val MAX_REPLY_DURATION: Duration = Duration.ofHours(2)

/** [idle] for connect, send and each read (silence between streamed chunks); the whole call
 *  may run up to [MAX_REPLY_DURATION]. */
internal fun streamingTimeout(idle: Duration): Timeout =
    Timeout.builder().connect(idle).read(idle).write(idle).request(MAX_REPLY_DURATION).build()

class ProviderConfigException(message: String) : IllegalArgumentException(message)

fun buildProviderFromType(
    type: String,
    apiKey: String?,
    baseUrl: String?,
    model: String,
    requestTimeout: Duration = Duration.ofSeconds(60),
    maxRetries: Int = 2,
    missingApiKeyMessage: String = "API key required for provider type 'claude' (pass apiKey or set ANTHROPIC_API_KEY)",
    missingBaseUrlMessage: String = "baseUrl is required for provider type 'openai-compat'",
    unknownTypeMessage: String = "Unknown provider type: $type (expected 'claude' or 'openai-compat')"
): LLMProvider = when (type.lowercase()) {
    "claude" -> {
        val key = apiKey ?: System.getenv("ANTHROPIC_API_KEY")
            ?: throw ProviderConfigException(missingApiKeyMessage)
        buildClaudeProvider(key, model)
    }
    "openai-compat" -> {
        val url = baseUrl ?: throw ProviderConfigException(missingBaseUrlMessage)
        buildOpenAiCompatProvider(url, apiKey, model, requestTimeout = requestTimeout, maxRetries = maxRetries)
    }
    else -> throw ProviderConfigException(unknownTypeMessage)
}
