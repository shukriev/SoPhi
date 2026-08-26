package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.learning.ToolStats
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * One LLM pass proposing a single [HarnessConfig] mutation, conditioned on real signal: lesson
 * failure-mode signatures with no addressing lesson yet ([unaddressedFailureModes]), and real
 * tool-reliability data ([toolStats] — [dev.sophi.learning.ToolStatsStore.stats], not
 * `ToolReliabilitySection`'s already-filtered prompt-text rendering). Falls back to returning
 * [incumbent] unchanged if the model's response doesn't parse — a mutation proposal that fails to
 * parse should not silently become a no-op-shaped challenger that gets recorded as if a real
 * mutation happened; the caller can tell "no change" apart from "a real proposal" simply by
 * comparing the result against [incumbent].
 */
suspend fun proposeMutation(
    provider: LLMProvider,
    model: String,
    incumbent: HarnessConfig,
    unaddressedFailureModes: List<String>,
    toolStats: Map<String, ToolStats>
): HarnessConfig {
    val prompt = buildString {
        appendLine("You are proposing ONE modification to an AI agent harness's configuration to improve its performance.")
        appendLine("Respond with ONLY a JSON object matching this exact shape (every field required, copy")
        appendLine("unchanged fields verbatim from the current config below):")
        appendLine(
            """{"systemPrompt":"...","temperature":0.7,"maxTokens":4096,"maxRecalledLessons":10,""" +
                """"criticEnabled":true,"topKSkills":null,"toolDescriptionOverrides":{}}"""
        )
        appendLine()
        appendLine("## Current config")
        appendLine("systemPrompt: ${incumbent.systemPrompt ?: "(none)"}")
        appendLine("temperature: ${incumbent.temperature}")
        appendLine("maxTokens: ${incumbent.maxTokens}")
        appendLine("maxRecalledLessons: ${incumbent.maxRecalledLessons}")
        appendLine("criticEnabled: ${incumbent.criticEnabled}")
        appendLine("topKSkills: ${incumbent.topKSkills ?: "(unlimited)"}")
        appendLine()
        appendLine("## Unaddressed failure modes (no lesson currently covers these)")
        appendLine(unaddressedFailureModes.joinToString("\n") { "- $it" }.ifEmpty { "(none)" })
        appendLine()
        appendLine("## Tool reliability stats")
        appendLine(
            toolStats.entries.joinToString("\n") { (tool, stats) ->
                "- $tool: attempts=${stats.attempts} failures=${stats.failures} streak=${stats.streak}"
            }.ifEmpty { "(none)" }
        )
        appendLine()
        appendLine("Propose exactly one focused change addressing one of the failure modes or reliability")
        appendLine("issues above. Keep every other field identical to the current config.")
    }

    val response = provider.complete(
        CompletionRequest(messages = listOf(Message(MessageRole.USER, prompt)), model = model, maxTokens = 2048, temperature = 0.7)
    )
    val text = (response as? LLMResponse.Text)?.content ?: return incumbent
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return incumbent
    return runCatching { json.decodeFromString(HarnessConfig.serializer(), text.substring(start, end + 1)) }.getOrDefault(incumbent)
}
