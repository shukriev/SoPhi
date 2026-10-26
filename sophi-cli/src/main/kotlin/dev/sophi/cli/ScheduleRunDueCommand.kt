package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.sophi.schedule.store.TaskStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class ScheduleRunDueCommand : CliktCommand(
    name = "run-due",
    help = "Run any scheduled/goal tasks that are currently due, then exit. Intended to be invoked by launchd/cron."
) {
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
        // launchd calls this every minute: only probe for a model server when a task is actually due.
        val now = System.currentTimeMillis()
        val due = TaskStore(Path.of(scheduleDirStr).resolve("tasks.json")).list()
            .any { it.enabled && (it.nextRunAtMs ?: Long.MAX_VALUE) <= now }
        if (!due) return@runBlocking
        val (engine, _) = exitOnBadPermissionRules { buildScheduleEngine(
            provider.resolveAndWarn(), provider.apiKey,
            Path.of(scheduleDirStr), Path.of(sessionsDirStr), Path.of(agentsDirStr), braveApiKeyOption,
            taskTimeoutSeconds, maxTokens,
            llmTimeoutSeconds = provider.timeoutSeconds, llmMaxRetries = provider.maxRetries
        ) }
        engine.tickOnce(now)
    }
}
