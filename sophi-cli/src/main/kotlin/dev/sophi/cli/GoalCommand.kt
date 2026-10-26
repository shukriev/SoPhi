package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class GoalCommand : CliktCommand(name = "goal", help = "Manually trigger goal-based (or any) scheduled tasks") {
    override fun run() = Unit
}

class GoalRunCommand : CliktCommand(name = "run", help = "Run a task immediately by id, regardless of its schedule") {
    private val id by argument()
    private val provider by ProviderOptions()
    private val scheduleDirStr: String by option("--schedule-dir")
        .default("${System.getProperty("user.home")}/.sophi/schedule")
    private val sessionsDirStr: String by option("--sessions-dir")
        .default("${System.getProperty("user.home")}/.sophi/sessions")
    private val agentsDirStr: String by option("--agents-dir")
        .default("${System.getProperty("user.home")}/.sophi/agents")
    private val braveApiKeyOption: String? by option("--brave-api-key")
    private val taskTimeoutSeconds: Long by option(
        "--task-timeout-seconds",
        help = "Hard cap on this run (all iterations combined for a goal task). Raise this for slow local models."
    ).long().default(300)
    private val maxTokens: Int by option(
        "--max-tokens",
        help = "Max completion tokens per turn. Raise this for local reasoning models — hidden " +
            "chain-of-thought counts against this budget, so a low value can exhaust it before " +
            "the model ever emits an answer or tool call."
    ).int().default(4096)

    override fun run() = runBlocking {
        val (engine, _) = exitOnBadPermissionRules { buildScheduleEngine(
            provider.resolveAndWarn(), provider.apiKey,
            Path.of(scheduleDirStr), Path.of(sessionsDirStr), Path.of(agentsDirStr), braveApiKeyOption,
            taskTimeoutSeconds, maxTokens,
            llmTimeoutSeconds = provider.timeoutSeconds, llmMaxRetries = provider.maxRetries
        ) }
        val record = engine.runNow(id)
        if (record == null) echo("No task found with id $id") else echo("${record.outcome::class.simpleName}: ${record.summary}")
    }
}
