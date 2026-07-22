package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class ScheduleRunDueCommand : CliktCommand(
    name = "run-due",
    help = "Run any scheduled/goal tasks that are currently due, then exit. Intended to be invoked by launchd/cron."
) {
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
        help = "Hard cap on one task's run (all iterations combined for goal tasks). " +
            "There's no one watching an unattended run to Ctrl-C a hang, so it fails as " +
            "RunOutcome.Failed instead of blocking forever. Raise this for slow local models."
    ).long().default(300)
    private val maxTokens: Int by option(
        "--max-tokens",
        help = "Max completion tokens per turn. Raise this for local reasoning models — hidden " +
            "chain-of-thought counts against this budget, so a low value can exhaust it before " +
            "the model ever emits an answer or tool call."
    ).int().default(4096)

    override fun run() = runBlocking {
        val engine = buildScheduleEngine(
            model, providerType, apiKeyOption, baseUrl,
            Path.of(scheduleDirStr), Path.of(sessionsDirStr), Path.of(agentsDirStr), braveApiKeyOption,
            taskTimeoutSeconds, maxTokens
        )
        engine.tickOnce()
    }
}
