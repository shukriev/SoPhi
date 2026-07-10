package dev.sophi.web.config

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.providers.BraveSearchProvider
import dev.sophi.ai.providers.buildClaudeProvider
import dev.sophi.ai.providers.buildOpenAiCompatProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.EditTool
import dev.sophi.core.tools.FetchUrlTool
import dev.sophi.core.tools.GlobTool
import dev.sophi.core.tools.GrepTool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.tools.WebSearchTool
import dev.sophi.extensions.PluginRegistry
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.config.McpConfigLoader
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import kotlin.io.path.exists

internal fun buildProviderFromProperties(props: ProviderProperties): LLMProvider = when (props.type.lowercase()) {
    "claude" -> {
        val apiKey = props.apiKey ?: System.getenv("ANTHROPIC_API_KEY")
            ?: throw IllegalStateException(
                "sophi.provider.api-key is required when sophi.provider.type=claude " +
                    "(neither sophi.provider.api-key nor ANTHROPIC_API_KEY was set)"
            )
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
    fun toolRegistry(mcpClientManager: McpClientManager): ToolRegistry {
        val registry = ToolRegistry()
            .register(GrepTool())
            .register(GlobTool())
            .register(EditTool())
            .register(BashTool())
            .register(FetchUrlTool())
        val braveApiKey = System.getenv("BRAVE_SEARCH_API_KEY")
        if (braveApiKey != null) {
            registry.register(WebSearchTool(BraveSearchProvider(braveApiKey)))
        }
        val mcpConfigPath = Path.of(System.getProperty("user.dir"), ".sophi", "mcp.json")
        if (mcpConfigPath.exists()) {
            val mcpConfig = McpConfigLoader().load(mcpConfigPath)
            runBlocking { mcpClientManager.connect(mcpConfig.servers) }.forEach { registry.register(it) }
        }
        return registry
    }

    @Bean
    fun confirmationPolicy(): ConfirmationPolicy = ConfirmationPolicy.DENY_DESTRUCTIVE

    @Bean(destroyMethod = "close")
    fun mcpClientManager(): McpClientManager = McpClientManager()

    @Bean
    fun sessionManager(): SessionManager =
        FileSessionManager(Path.of(System.getProperty("user.home"), ".sophi", "sessions"))

    @Bean
    fun learningConfig(): LearningConfig = LearningConfig(sessionModel = providerProperties.model)

    @Bean
    fun learningPlugin(
        learningConfig: LearningConfig,
        llmProvider: LLMProvider,
        sessionManager: SessionManager
    ): LearningPlugin =
        LearningPlugin(learningConfig, model = providerProperties.model, provider = llmProvider, sessionManager = sessionManager)

    @Bean
    fun pluginRegistry(learningPlugin: LearningPlugin): PluginRegistry =
        PluginRegistry().register(learningPlugin)

    @Bean
    fun agentConfig(learningPlugin: LearningPlugin, learningConfig: LearningConfig): AgentConfig {
        val section = learningPlugin.promptSections(learningConfig.scope)
        return AgentConfig(model = providerProperties.model, systemPrompt = section)
    }

    @Bean
    fun agentLoop(
        llmProvider: LLMProvider,
        toolRegistry: ToolRegistry,
        sessionManager: SessionManager,
        confirmationPolicy: ConfirmationPolicy
    ): AgentLoop = AgentLoop(llmProvider, toolRegistry, sessionManager, confirmationPolicy = confirmationPolicy)
}
