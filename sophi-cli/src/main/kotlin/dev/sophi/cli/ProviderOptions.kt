package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.providers.LocalServer
import dev.sophi.ai.providers.NO_LOCAL_SERVER_MESSAGE
import dev.sophi.ai.providers.detectContextWindow
import dev.sophi.ai.providers.discoverLocalServer

/** The model-provider options every command that talks to a model shares. */
class ProviderOptions : OptionGroup("Model provider") {
    val type: String by option(
        "--provider",
        help = "local (default: finds llama.cpp, LM Studio or Ollama), openai-compat, or claude"
    ).default("local")
    val model: String? by option(
        "--model", "-m",
        help = "Model name. Optional for local (the server's first loaded model); required otherwise."
    )
    val baseUrl: String? by option(
        "--base-url",
        help = "Server URL ending in /v1, e.g. http://localhost:11434/v1. Optional for local (probed), required for openai-compat."
    )
    val apiKey: String? by apiKeyOption()
    val timeoutSeconds: Long by option(
        "--llm-timeout-seconds",
        help = "Longest silence to wait for from the model. Raise this for local reasoning models, " +
            "which can think for well over a minute before producing any output."
    ).long().default(60)
    val maxRetries: Int by option(
        "--llm-max-retries",
        help = "Retries on request failure. Each retry is subject to the full --llm-timeout-seconds, " +
            "so lower this (e.g. to 0) for a slow model that's consistently near the timeout."
    ).int().default(2)
    val contextWindowTokens: Int? by option(
        "--context-window-tokens",
        help = "Context window of --model, in tokens. Earlier tool rounds are summarised once 80% of it " +
            "is used. Default: read from a local server, else 8192 (openai-compat) or 200000 (claude)."
    ).int()
}

/** What [ProviderOptions] settled on: the probing and defaulting is done, so commands just use it. */
data class ResolvedProvider(
    val type: String,
    val baseUrl: String?,
    val model: String,
    val contextWindowTokens: Int,
    val embeddingModel: String?,
    val warnings: List<String>
)

fun ProviderOptions.resolve(discover: () -> LocalServer? = { discoverLocalServer() }): ResolvedProvider =
    when (val t = type.lowercase()) {
        "local" -> {
            val url = baseUrl
            val server = (if (url != null) discoverLocalServer(listOf(null to url)) else discover())
                ?: throw UsageError(NO_LOCAL_SERVER_MESSAGE)
            // LM Studio lists embedding models alongside chat models; never chat with one.
            val chosen = model ?: server.models.firstOrNull { "embed" !in it.lowercase() }
                ?: throw UsageError("The local server at ${server.baseUrl} has no model loaded. Load one, or pass --model.")
            val warnings = mutableListOf<String>()
            val window = contextWindowTokens
                ?: detectContextWindow(server, chosen)?.also { w -> w.warning?.let(warnings::add) }?.tokens
                ?: 8192.also {
                    warnings += "Couldn't read the server's context window; assuming 8192. Pass --context-window-tokens to set it."
                }
            ResolvedProvider(t, server.baseUrl, chosen, window, server.models.firstOrNull { "nomic-embed" in it }, warnings)
        }
        "openai-compat", "claude" -> ResolvedProvider(
            t, baseUrl, model ?: throw UsageError("--model is required with --provider $t"),
            contextWindowTokens ?: if (t == "claude") 200_000 else 8192, null, emptyList()
        )
        else -> throw UsageError("Unknown provider: $type (expected 'local', 'openai-compat' or 'claude')")
    }

/** [resolve] at a command's startup: prints the warnings to stderr once. */
internal fun ProviderOptions.resolveAndWarn(): ResolvedProvider =
    resolve().also { r -> r.warnings.forEach { System.err.println(it) } }

internal fun ProviderOptions.buildProvider(resolved: ResolvedProvider): LLMProvider =
    buildProvider(resolved, apiKey, timeoutSeconds, maxRetries)
