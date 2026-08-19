package dev.sophi.web.config

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.providers.BraveSearchProvider
import dev.sophi.ai.providers.ProviderConfigException
import dev.sophi.ai.providers.buildProviderFromType
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
import dev.sophi.schedule.store.TaskStore
import dev.sophi.schedule.tools.ScheduleTaskTool
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import kotlin.io.path.exists

internal fun buildProviderFromProperties(props: ProviderProperties): LLMProvider = try {
    buildProviderFromType(
        props.type, props.apiKey, props.baseUrl, props.model,
        missingApiKeyMessage = "sophi.provider.api-key is required when sophi.provider.type=claude " +
            "(neither sophi.provider.api-key nor ANTHROPIC_API_KEY was set)",
        missingBaseUrlMessage = "sophi.provider.base-url is required when sophi.provider.type=openai-compat",
        unknownTypeMessage = "Unknown sophi.provider.type: ${props.type} (expected 'claude' or 'openai-compat')"
    )
} catch (e: ProviderConfigException) {
    throw IllegalStateException(e.message, e)
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
            .register(ScheduleTaskTool(
                TaskStore(Path.of(System.getProperty("user.home"), ".sophi", "schedule", "tasks.json")),
                dev.sophi.schedule.store.RunLog(Path.of(System.getProperty("user.home"), ".sophi", "schedule", "runs.jsonl"))
            ))
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
    fun confirmationPolicy(): ConfirmationPolicy = ConfirmationPolicy.DENY_ALL

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
        // promptSections() is reliability content only — evaluated once at bean creation (startup)
        // is fine for it, since it doesn't need per-turn context. Lessons are delivered separately,
        // per turn, via AgentController.configWithContext()'s collectContext() call — not baked in
        // here — since they depend on that turn's actual input.
        val section = learningPlugin.promptSections(learningConfig.scope)
        return AgentConfig(model = providerProperties.model, systemPrompt = section)
    }

    @Bean
    fun agentLoop(
        llmProvider: LLMProvider,
        toolRegistry: ToolRegistry,
        sessionManager: SessionManager,
        confirmationPolicy: ConfirmationPolicy
    ): AgentLoop {
        val window = providerProperties.contextWindowTokens
            ?: throw IllegalStateException(
                "sophi.provider.context-window-tokens is required — set it to the total context " +
                    "window (in tokens) of sophi.provider.model"
            )
        return AgentLoop(
            llmProvider, toolRegistry, sessionManager,
            confirmationPolicy = confirmationPolicy,
            contextWindowTokens = window
        )
    }
}
