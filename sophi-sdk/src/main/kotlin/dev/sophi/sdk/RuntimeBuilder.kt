package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.SophiPlugin
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.config.McpConfigLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class RuntimeBuilder {
    var provider: LLMProvider? = null
    var model: String = "claude-sonnet-4-5"
    var maxTokens: Int = 4096
    var systemPrompt: String? = null
    var sessionsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "sessions")

    private val tools: MutableList<Tool> = mutableListOf()
    private val plugins: MutableList<SophiPlugin> = mutableListOf()
    private var confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.DENY_DESTRUCTIVE
    private var mcpConfigPath: Path? = null
    private var mcpClientManager: McpClientManager = McpClientManager()
    private var learningConfig: LearningConfig? = null

    fun tool(t: Tool): RuntimeBuilder = apply { tools.add(t) }
    fun plugin(p: SophiPlugin): RuntimeBuilder = apply { plugins.add(p) }
    fun confirmationPolicy(policy: ConfirmationPolicy): RuntimeBuilder = apply { confirmationPolicy = policy }
    fun mcpConfig(path: Path): RuntimeBuilder = apply { mcpConfigPath = path }
    fun mcpClientManager(manager: McpClientManager): RuntimeBuilder = apply { mcpClientManager = manager }
    fun learning(config: LearningConfig): RuntimeBuilder = apply { learningConfig = config }

    fun build(): SophiRuntime {
        val p = requireNotNull(provider) { "provider must be set before calling build()" }
        val registry = ToolRegistry().also { r -> tools.forEach { r.register(it) } }
        val mcpServers = mcpConfigPath?.let { McpConfigLoader().load(it).servers } ?: emptyList()
        runBlocking { mcpClientManager.connect(mcpServers) }.forEach { registry.register(it) }
        val sm = FileSessionManager(sessionsDir)
        val agentConfig = AgentConfig(
            model = model,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt
        )
        val loop = AgentLoop(p, registry, sm, confirmationPolicy = confirmationPolicy)
        val pluginRegistry = PluginRegistry().also { r -> plugins.forEach { r.register(it) } }

        val learningPlugin = learningConfig?.let { cfg ->
            LearningPlugin(cfg.copy(sessionModel = agentConfig.model), model = agentConfig.model, provider = p, sessionManager = sm).also { plugin ->
                pluginRegistry.register(plugin)
            }
        }
        val effectiveConfig = learningPlugin?.let { plugin ->
            val section = plugin.promptSections(learningConfig!!.scope)
            if (section != null)
                agentConfig.copy(
                    systemPrompt = listOfNotNull(agentConfig.systemPrompt, section).joinToString("\n\n")
                )
            else agentConfig
        } ?: agentConfig

        return SophiRuntime(loop, sm, pluginRegistry, effectiveConfig, mcpClientManager, learningPlugin)
    }
}
