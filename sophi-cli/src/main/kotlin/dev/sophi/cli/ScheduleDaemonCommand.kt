package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class ScheduleDaemonCommand : CliktCommand(
    name = "daemon",
    help = "Run sophi schedule run-due on a loop inside this process (convenience alternative to launchd/cron)."
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
    private val intervalSeconds: Long by option("--interval-seconds").long().default(60)
    private val taskTimeoutSeconds: Long by option(
        "--task-timeout-seconds",
        help = "Hard cap on one task's run. Especially important here: without it, one stuck " +
            "task's turn blocks tickOnce() from ever returning, freezing every future tick of " +
            "this daemon, not just the stuck task. Raise this for slow local models."
    ).long().default(300)
    private val maxTokens: Int by option(
        "--max-tokens",
        help = "Max completion tokens per turn. Raise this for local reasoning models — hidden " +
            "chain-of-thought counts against this budget, so a low value can exhaust it before " +
            "the model ever emits an answer or tool call."
    ).int().default(4096)
    private val contextWindowTokens: Int by option(
        "--context-window-tokens",
        help = "Total context window of --model, in tokens. The turn's earlier tool rounds are " +
            "summarised once 80% of this is used, instead of capping the number of rounds."
    ).int().default(200_000)

    // If this daemon's runtime is ever given .memory(...), re-check ADR-026's single-process
    // ArcadeDB lock constraint first — a memory-enabled interactive session or the companion
    // could otherwise contend with this daemon for the same Jane's Palace database file.
    override fun run() = runBlocking {
        val engine = buildScheduleEngine(
            model, providerType, apiKeyOption, baseUrl,
            Path.of(scheduleDirStr), Path.of(sessionsDirStr), Path.of(agentsDirStr), braveApiKeyOption,
            contextWindowTokens, taskTimeoutSeconds, maxTokens
        )
        bootstrapOrchestrator(dev.sophi.schedule.store.TaskStore(Path.of(scheduleDirStr).resolve("tasks.json")))
        while (true) {
            runCatching { engine.tickOnce() }
            delay(intervalSeconds * 1000)
        }
    }
}
