package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class SophiCli : CliktCommand(name = "sophi", help = "Sophi — Kotlin agent harness") {

    private val sessionId: String? by option(
        "--session", "-s",
        help = "Session ID to resume (omit to start a new session)"
    )
    private val model: String by option(
        "--model", "-m",
        help = "LLM model name"
    ).default("claude-3-5-sonnet-20241022")
    private val sessionsDirStr: String by option(
        "--sessions-dir",
        help = "Directory for session JSONL files"
    ).default("${System.getProperty("user.home")}/.sophi/sessions")
    private val systemPrompt: String? by option(
        "--system",
        help = "System prompt injected into every turn"
    )

    override fun run() = runBlocking {
        val apiKey = System.getenv("ANTHROPIC_API_KEY")
            ?: throw UsageError("ANTHROPIC_API_KEY environment variable is not set")

        val provider = buildClaudeProvider(apiKey, model)
        val registry = ToolRegistry()
        val sessionManager = FileSessionManager(Path.of(sessionsDirStr))
        val config = AgentConfig(model = model, systemPrompt = systemPrompt)
        val loop = AgentLoop(provider, registry, sessionManager)
        val compactor = ContextCompactor(provider)
        val terminal = Terminal()

        val session = sessionId?.let { sessionManager.load(it) } ?: sessionManager.create()

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
