package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
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

    override fun run() = runBlocking {
        val engine = buildScheduleEngine(
            model, providerType, apiKeyOption, baseUrl,
            Path.of(scheduleDirStr), Path.of(sessionsDirStr), Path.of(agentsDirStr), braveApiKeyOption
        )
        while (true) {
            runCatching { engine.tickOnce() }
            delay(intervalSeconds * 1000)
        }
    }
}
