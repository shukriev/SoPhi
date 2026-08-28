package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
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
    private val memoryEnabled: Boolean by option(
        "--memory",
        help = "Enable Jane's Theory long-term memory for scheduled task outcomes (experimental). " +
            "Requires --embedding-model and --embedding-base-url. Defaults to a SEPARATE memory " +
            "home from interactive `sophi`/`sophi-companion` (~/.sophi/memory-scheduled) to avoid " +
            "ArcadeDB's single-process lock (ADR-026) — override with --memory-home if you want a " +
            "single shared store and accept that constraint."
    ).flag(default = false)
    private val memoryHomeStr: String by option(
        "--memory-home",
        help = "Path for this daemon's memory store when --memory is set."
    ).default("${System.getProperty("user.home")}/.sophi/memory-scheduled")
    private val embeddingModel: String? by option(
        "--embedding-model",
        help = "Embedding model name for --memory, e.g. nomic-embed-text (Ollama) or text-embedding-3-small"
    )
    private val embeddingBaseUrl: String? by option(
        "--embedding-base-url",
        help = "Embeddings endpoint base URL (defaults to --base-url; e.g. http://localhost:11434/v1)"
    )
    private val embeddingDimensions: Int by option(
        "--embedding-dimensions",
        help = "Embedding vector dimensions (768 for nomic-embed-text, 1536 for text-embedding-3-small)"
    ).int().default(1536)

    override fun run() = runBlocking {
        val scheduleRuntime = try {
            buildScheduleEngine(
                model, providerType, apiKeyOption, baseUrl,
                Path.of(scheduleDirStr), Path.of(sessionsDirStr), Path.of(agentsDirStr), braveApiKeyOption,
                contextWindowTokens, taskTimeoutSeconds, maxTokens,
                memoryHome = if (memoryEnabled) Path.of(memoryHomeStr) else null,
                embeddingModel = embeddingModel,
                embeddingBaseUrl = embeddingBaseUrl ?: baseUrl,
                embeddingApiKey = apiKeyOption,
                embeddingDimensions = embeddingDimensions,
                onWarning = { echo(it) }
            )
        } catch (e: com.arcadedb.exception.DatabaseOperationException) {
            echo(
                "Cannot start with --memory: another instance in this JVM already has " +
                    "$memoryHomeStr open. See ADR-026 (doc/adr/) — only one process may hold a " +
                    "given memory home open at a time."
            )
            return@runBlocking
        } catch (e: com.arcadedb.utility.LockException) {
            echo(
                "Cannot start with --memory: $memoryHomeStr is already locked by another process " +
                    "(interactive sophi, sophi-companion, or another daemon instance). See ADR-026 " +
                    "(doc/adr/) — only one process may hold a given memory home open at a time."
            )
            return@runBlocking
        }
        val engine = scheduleRuntime.engine
        val memoryPlugin = scheduleRuntime.memoryPlugin
        Runtime.getRuntime().addShutdownHook(Thread { memoryPlugin?.close() })
        bootstrapOrchestrator(dev.sophi.schedule.store.TaskStore(Path.of(scheduleDirStr).resolve("tasks.json")))
        while (true) {
            runCatching { engine.tickOnce() }
            memoryPlugin?.let { runCatching { it.consolidateIfDue() } }
            delay(intervalSeconds * 1000)
        }
    }
}
