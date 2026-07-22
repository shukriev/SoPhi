package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class GoalCommand : CliktCommand(name = "goal", help = "Manually trigger goal-based (or any) scheduled tasks") {
    override fun run() = Unit
}

class GoalRunCommand : CliktCommand(name = "run", help = "Run a task immediately by id, regardless of its schedule") {
    private val id by argument()
    private val model: String by option("--model", "-m").default("claude-3-5-sonnet-20241022")
    private val providerType: String by option("--provider").default("claude")
    private val baseUrl: String? by option("--base-url")
    private val apiKeyOption: String? by option("--api-key")
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

    override fun run() = runBlocking {
        val engine = buildScheduleEngine(
            model, providerType, apiKeyOption, baseUrl,
            Path.of(scheduleDirStr), Path.of(sessionsDirStr), Path.of(agentsDirStr), braveApiKeyOption,
            taskTimeoutSeconds
        )
        val record = engine.runNow(id)
        if (record == null) echo("No task found with id $id") else echo("${record.outcome::class.simpleName}: ${record.summary}")
    }
}
