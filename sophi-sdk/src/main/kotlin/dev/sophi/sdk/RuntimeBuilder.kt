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

    fun tool(t: Tool): RuntimeBuilder = apply { tools.add(t) }
    fun plugin(p: SophiPlugin): RuntimeBuilder = apply { plugins.add(p) }
    fun confirmationPolicy(policy: ConfirmationPolicy): RuntimeBuilder = apply { confirmationPolicy = policy }

    fun build(): SophiRuntime {
        val p = requireNotNull(provider) { "provider must be set before calling build()" }
        val registry = ToolRegistry().also { r -> tools.forEach { r.register(it) } }
        val sm = FileSessionManager(sessionsDir)
        val agentConfig = AgentConfig(
            model = model,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt
        )
        val loop = AgentLoop(p, registry, sm, confirmationPolicy = confirmationPolicy)
        val pluginRegistry = PluginRegistry().also { r -> plugins.forEach { r.register(it) } }
        return SophiRuntime(loop, sm, pluginRegistry, agentConfig)
    }
}
