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
import dev.sophi.ai.providers.isChatModel
import dev.sophi.ai.providers.loadedChatModels
import dev.sophi.sdk.ModelCatalog
import dev.sophi.sdk.ModelInfo
import java.net.InetAddress
import java.net.URI
import java.time.Duration

/** The model-provider options every command that talks to a model shares. */
class ProviderOptions : OptionGroup("Model provider") {
    val type: String by option(
        "--provider",
        help = "local (default: finds llama.cpp, LM Studio or Ollama), openai-compat, or claude"
    ).default("local")
    val model: String? by option(
        "--model", "-m",
        help = "Model name. Optional for local (a model the server has loaded); required otherwise."
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
            "is used. Default: read from a local server, else the model catalog, else 8192 (openai-compat) or 200000 (claude)."
    ).int()
}

/** What [ProviderOptions] settled on: the probing and defaulting is done, so commands just use it. */
data class ResolvedProvider(
    val type: String,
    val baseUrl: String?,
    val model: String,
    val contextWindowTokens: Int,
    val embeddingModel: String?,
    val warnings: List<String>,
    /** A billing API: `claude`, or `openai-compat` on a public host. Local servers are free. */
    val cloud: Boolean = false,
    /** The catalog provider whose entry to prefer (`anthropic`, `openrouter`, `openai`), when known. */
    val catalogProvider: String? = null,
    /** What the catalog says about [model]: its own entry for cloud, its base model's capabilities for local. */
    val info: ModelInfo? = null,
    /** The local server [model] runs on, so a later `/model` can ask it for a window. */
    val server: LocalServer? = null,
)

private val IP_LITERAL = Regex("""[0-9.]+|[0-9a-f:]+""")

/**
 * Hosts that are this machine or its network: `localhost`, `*.local`, `*.internal`, a bare name with no
 * dot (`gpu-box`), and loopback, site-local, link-local or tailnet (100.64.0.0/10) IPs.
 */
internal fun isLocalHost(host: String?): Boolean {
    val h = host?.lowercase()?.removeSurrounding("[", "]") ?: return true
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".localhost") || h.endsWith(".internal")) return true
    if (!IP_LITERAL.matches(h) && '.' !in h) return true
    // Only IP literals are checked; a name is never resolved here, so it counts as public.
    if (!IP_LITERAL.matches(h) || h.none { it == '.' || it == ':' }) return false
    val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
    val b = a.address
    val tailnet = b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64 // CGNAT, used by Tailscale
    return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || tailnet
}

/** `claude` → `anthropic`; an openai-compat base URL's host → the catalog provider that serves it. */
internal fun catalogProviderFor(type: String, baseUrl: String?): String? = when (type) {
    "claude" -> "anthropic"
    else -> when (runCatching { URI(baseUrl ?: "").host }.getOrNull()?.lowercase()) {
        "openrouter.ai" -> "openrouter"
        "api.openai.com" -> "openai"
        else -> null
    }
}

fun ProviderOptions.resolve(
    catalog: ModelCatalog = ModelCatalog.bundled(),
    discover: () -> LocalServer? = { discoverLocalServer() },
): ResolvedProvider =
    when (val t = type.lowercase()) {
        "local" -> {
            val url = baseUrl?.trimEnd('/')
            // A named server may be across the LAN, so it gets longer than the loopback discovery probe.
            val server = if (url != null) {
                discoverLocalServer(listOf(null to url), Duration.ofSeconds(5)) ?: throw UsageError(
                    "No OpenAI-compatible server answered at $url/models. Check that it is running and that " +
                        "--base-url ends in /v1 (e.g. http://localhost:1234/v1)."
                )
            } else discover() ?: throw UsageError(NO_LOCAL_SERVER_MESSAGE)
            // `/models` lists every downloaded model; prefer one already in memory over one that must load first.
            val chosen = model ?: loadedChatModels(server).firstOrNull() ?: server.models.firstOrNull(::isChatModel)
                ?: throw UsageError("The local server at ${server.baseUrl} has no model loaded. Load one, or pass --model.")
            val warnings = mutableListOf<String>()
            val window = contextWindowTokens
                ?: detectContextWindow(server, chosen)?.also { w -> w.warning?.let(warnings::add) }?.tokens
                ?: 8192.also {
                    warnings += "Couldn't read the server's context window; assuming 8192. Pass --context-window-tokens to set it."
                }
            ResolvedProvider(t, server.baseUrl, chosen, window, server.models.firstOrNull { "nomic-embed" in it }, warnings,
                info = catalog.base(chosen), server = server)
        }
        "openai-compat", "claude" -> {
            val chosen = model ?: throw UsageError("--model is required with --provider $t")
            val cloud = t == "claude" || !isLocalHost(runCatching { URI(baseUrl ?: "").host }.getOrNull())
            val catalogProvider = catalogProviderFor(t, baseUrl)
            // An openai-compat server on this machine or network runs a local model: no price, no catalog window.
            val info = if (cloud) catalog.find(chosen, catalogProvider) else catalog.base(chosen)
            ResolvedProvider(
                t, baseUrl, chosen,
                contextWindowTokens ?: info?.contextTokens ?: if (t == "claude") 200_000 else 8192, null, emptyList(),
                cloud = cloud, catalogProvider = catalogProvider, info = info,
            )
        }
        else -> throw UsageError("Unknown provider: $type (expected 'local', 'openai-compat' or 'claude')")
    }

/** The resolved window, capped at a command's fixed [cap]: an eval budget must not exceed what the server loaded. */
internal fun ResolvedProvider.windowAtMost(cap: Int): Int = minOf(contextWindowTokens, cap)

/** [resolve] at a command's startup: prints the warnings to stderr once. */
internal fun ProviderOptions.resolveAndWarn(catalog: ModelCatalog = ModelCatalog.bundled()): ResolvedProvider =
    resolve(catalog).also { r -> r.warnings.forEach { System.err.println(it) } }

internal fun ProviderOptions.buildProvider(resolved: ResolvedProvider, inner: (LLMProvider) -> LLMProvider = { it }): LLMProvider =
    buildProvider(resolved, apiKey, timeoutSeconds, maxRetries, inner = inner)

/** What `/model <id>` switches to: the new window, or why it can't switch. */
data class ModelSwitch(val window: Int? = null, val error: String? = null)

/**
 * The window for switching to [id]: a local server's own (and it must list [id]), the catalog's for a
 * cloud model, else [current] — never the catalog's for a local model, whose real window may be far smaller.
 */
internal fun modelSwitchWindow(
    resolved: ResolvedProvider,
    catalog: ModelCatalog,
    id: String,
    current: Int,
    detect: (LocalServer, String) -> Int? = { server, model -> detectContextWindow(server, model)?.tokens },
): ModelSwitch {
    val server = resolved.server
    if (server != null) {
        if (id !in server.models) return ModelSwitch(error = "$id isn't on the server at ${server.baseUrl}")
        return ModelSwitch(window = detect(server, id) ?: current)
    }
    if (!resolved.cloud) return ModelSwitch(window = current)
    return ModelSwitch(window = catalog.find(id, resolved.catalogProvider)?.contextTokens ?: current)
}
