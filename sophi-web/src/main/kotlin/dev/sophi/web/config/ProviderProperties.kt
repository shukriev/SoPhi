package dev.sophi.web.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "sophi.provider")
data class ProviderProperties(
    val type: String = "claude",
    val model: String = "claude-3-5-sonnet-20241022",
    val baseUrl: String? = null,
    val apiKey: String? = null,
    /**
     * Total context window of [model], in tokens. Required — there is deliberately no per-model
     * registry, because whoever configures `model` is the only one who knows its window.
     */
    val contextWindowTokens: Int? = null
)
