package dev.sophi.web.config

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.providers.buildClaudeProvider
import dev.sophi.ai.providers.buildOpenAiCompatProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

internal fun buildProviderFromProperties(props: ProviderProperties): LLMProvider = when (props.type) {
    "claude" -> {
        val apiKey = props.apiKey
            ?: throw IllegalStateException("sophi.provider.api-key is required when sophi.provider.type=claude")
        buildClaudeProvider(apiKey, props.model)
    }
    "openai-compat" -> {
        val baseUrl = props.baseUrl
            ?: throw IllegalStateException("sophi.provider.base-url is required when sophi.provider.type=openai-compat")
        buildOpenAiCompatProvider(baseUrl, props.apiKey, props.model, name = "openai-compat")
    }
    else -> throw IllegalStateException(
        "Unknown sophi.provider.type: ${props.type} (expected 'claude' or 'openai-compat')"
    )
}

@Configuration
@EnableConfigurationProperties(ProviderProperties::class)
class AgentConfiguration(private val providerProperties: ProviderProperties) {

    @Bean
    fun llmProvider(): LLMProvider = buildProviderFromProperties(providerProperties)

    @Bean
    fun toolRegistry(): ToolRegistry = ToolRegistry()

    @Bean
    fun sessionManager(): SessionManager =
        FileSessionManager(Path.of(System.getProperty("user.home"), ".sophi", "sessions"))

    @Bean
    fun agentConfig(): AgentConfig = AgentConfig(model = providerProperties.model)

    @Bean
    fun agentLoop(llmProvider: LLMProvider, toolRegistry: ToolRegistry, sessionManager: SessionManager): AgentLoop =
        AgentLoop(llmProvider, toolRegistry, sessionManager)
}
