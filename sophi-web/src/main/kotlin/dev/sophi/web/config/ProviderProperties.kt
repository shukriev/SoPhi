package dev.sophi.web.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "sophi.provider")
data class ProviderProperties(
    val type: String = "claude",
    /** Required: SoPhi never picks a model (least of all a cloud one) for the user. */
    val model: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    /**
     * Total context window of [model], in tokens. Required — there is deliberately no per-model
     * registry, because whoever configures `model` is the only one who knows its window.
     */
    val contextWindowTokens: Int? = null
) {
    fun requireModel(): String = model ?: throw IllegalStateException(MISSING_MODEL_MESSAGE)

    companion object {
        const val MISSING_MODEL_MESSAGE = "sophi.provider.model is required — set it to the model to run " +
            "(e.g. qwen3:8b on a local server, or a Claude model id with sophi.provider.type=claude)"
    }
}
