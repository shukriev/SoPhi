package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import dev.sophi.ai.api.LLMProvider
import dev.sophi.sdk.briefing
import dev.sophi.sdk.briefingWindowStart
import dev.sophi.sdk.markBriefed
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * `sophi briefing`'s text for saved [settings]: since the last briefing (24h the first time), with a lede from
 * [model] (provider and model name) when it resolves; when it throws, the template alone. Then marks the user briefed.
 */
internal suspend fun runBriefing(settings: Map<String, String>, nowMs: Long, model: () -> Pair<LLMProvider, String>): String {
    val dir = settings["schedule-dir"]?.let { Path.of(it) } ?: defaultScheduleDir()
    val m = runCatching(model).getOrNull()
    return briefing(dir, briefingWindowStart(dir, nowMs), nowMs, m?.first, m?.second).also { markBriefed(dir, nowMs) }
}

class BriefingCommand : CliktCommand(name = "briefing", help = "What scheduled tasks did since your last briefing (the last 24h the first time)") {
    override fun run() = runBlocking {
        val settings = CliSettingsStore { System.err.println(it) }.usable()
        echo(runBriefing(settings, System.currentTimeMillis()) {
            // The session's configured model, local first (as `sophi doctor` resolves it); one try, no retries.
            val r = resolveProvider(settings["provider"] ?: "local", settings["base-url"], settings["model"],
                settings["context-window-tokens"]?.toIntOrNull(), loadModelCatalog { })
            buildProvider(r, null, settings["llm-timeout-seconds"]?.toLongOrNull() ?: 60, maxRetries = 0) to r.model
        })
    }
}
