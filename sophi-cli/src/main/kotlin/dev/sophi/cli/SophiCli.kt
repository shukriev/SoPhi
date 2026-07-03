package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.SubagentTool
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.FileReadTool
import dev.sophi.core.tools.FileWriteTool
import dev.sophi.core.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import java.nio.file.Path

class SophiCli : CliktCommand(name = "sophi", help = "Sophi — Kotlin agent harness") {

    private val sessionId: String? by option(
        "--session", "-s",
        help = "Session ID to resume (omit to start a new session)"
    )
    private val model: String by option(
        "--model", "-m",
        help = "LLM model name (for --provider openai-compat, always set this explicitly — e.g. qwen2.5:7b)"
    ).default("claude-3-5-sonnet-20241022")
    private val providerType: String by option(
        "--provider",
        help = "LLM provider: 'claude' (default) or 'openai-compat' (Ollama, vLLM, or any OpenAI-compatible server)"
    ).default("claude")
    private val baseUrl: String? by option(
        "--base-url",
        help = "Base URL for --provider openai-compat, e.g. http://localhost:11434/v1 (Ollama) or http://localhost:8000/v1 (vLLM)"
    )
    private val apiKeyOption: String? by option(
        "--api-key",
        help = "API key (falls back to ANTHROPIC_API_KEY for --provider claude; omit for no-auth local servers)"
    )
    private val sessionsDirStr: String by option(
        "--sessions-dir",
        help = "Directory for session JSONL files"
    ).default("${System.getProperty("user.home")}/.sophi/sessions")
    private val agentsDirStr: String by option(
        "--agents-dir",
        help = "Directory of subagent definition Markdown files"
    ).default("${System.getProperty("user.home")}/.sophi/agents")
    private val systemPrompt: String? by option(
        "--system",
        help = "System prompt injected into every turn"
    )

    override fun run() = runBlocking {
        val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
        val sessionManager = FileSessionManager(Path.of(sessionsDirStr))
        val session = sessionId?.let { sessionManager.load(it) } ?: sessionManager.create()
        val config = AgentConfig(model = model, systemPrompt = systemPrompt)

        val agentsDir = Path.of(agentsDirStr).also { it.createDirectories() }
        val agentDefinitions = AgentDefinitionLoader().load(agentsDir)

        val registry = ToolRegistry()
            .register(FileReadTool())
            .register(FileWriteTool())
        if (agentDefinitions.isNotEmpty()) {
            registry.register(
                SubagentTool(
                    definitions = agentDefinitions,
                    provider = provider,
                    fullRegistry = registry,
                    sessionManager = sessionManager,
                    parentSessionId = session.id,
                    parentConfig = config
                )
            )
        }

        val loop = AgentLoop(provider, registry, sessionManager)
        val compactor = ContextCompactor(provider)
        val terminal = Terminal()

        terminal.println(TextColors.cyan("Sophi — session ${session.id}"))
        terminal.println("Type 'exit' or 'quit' to end. Commands: /list /branch /checkout /compact\n")

        val slashHandler = SlashHandler(sessionManager, compactor, config) { terminal.println(it) }
        val engine = TuiEngine(loop, slashHandler, config, terminal)

        engine.run(session, generateSequence {
            terminal.print(TextColors.blue("You: "))
            readLine()
        })

        terminal.println(TextColors.cyan("\nSession ${session.id} ended."))
    }
}
