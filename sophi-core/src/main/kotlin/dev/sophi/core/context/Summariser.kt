package dev.sophi.core.context

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import kotlin.coroutines.cancellation.CancellationException

enum class CompactionMode {
    GENERIC, ELIDE, STRUCTURED;

    val elides: Boolean get() = this != GENERIC
}

private const val GENERIC_PROMPT = "Summarise the following steps of an agent's work concisely, preserving key " +
    "facts, findings and decisions. Do not invent anything."

private const val STRUCTURED_PROMPT = """You are compacting an agent's working session so it can continue without the full history.
Write exactly these four sections, with these headings, using only facts in the transcript:
## Goal
## Decisions & findings
## Errors & attempts
## Open items
Be specific (names, paths, values). Do not list files or commands; they are added separately."""

private const val NOT_STATED = "(not stated)"

/** The one LLM call of a compaction. STRUCTURED text is assembled by code: LLM prose plus exact WorkingSet sections. */
class Summariser(
    private val provider: LLMProvider,
    val mode: CompactionMode = CompactionMode.STRUCTURED,
    private val windowTokens: Int
) {
    val budgetTokens: Int =
        if (mode == CompactionMode.STRUCTURED) (windowTokens * 0.03).toInt().coerceIn(400, 2_000) else 512

    suspend fun summarise(
        transcript: String,
        workingSet: WorkingSet,
        model: String,
        onUsage: (TokenUsage) -> Unit = {}
    ): String {
        val structured = mode == CompactionMode.STRUCTURED
        val request = CompletionRequest(
            messages = listOf(
                Message(MessageRole.SYSTEM, if (structured) STRUCTURED_PROMPT else GENERIC_PROMPT),
                Message(MessageRole.USER, transcript)
            ),
            model = model,
            maxTokens = budgetTokens,
            temperature = 0.3,
            reasoningEffort = "none"
        )
        val text = try {
            (provider.complete(request) as? LLMResponse.Text)?.also { runCatching { onUsage(it.usage) } }?.content
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // A failed summarisation must not abort the turn: degrade to the truncated transcript.
        val body = text ?: transcript.lines().joinToString("; ") { it.take(80) }
        return if (structured) assemble(body, workingSet, fromLlm = text != null) else body
    }

    private fun assemble(body: String, ws: WorkingSet, fromLlm: Boolean): String {
        val sections = if (fromLlm) parse(body) else mapOf("Goal" to body)
        fun llm(name: String) = sections[name]?.takeIf { it.isNotBlank() } ?: NOT_STATED
        val errors = listOfNotNull(sections["Errors & attempts"]?.takeIf { it.isNotBlank() }, ws.errorsSection().takeIf { it != "(none)" })
            .joinToString("\n").ifEmpty { NOT_STATED }
        return listOf(
            "Goal" to llm("Goal"),
            "Decisions & findings" to llm("Decisions & findings"),
            "Files touched" to ws.filesSection(),
            "Commands & outcomes" to ws.commandsSection(),
            "Errors & attempts" to errors,
            "Open items" to llm("Open items")
        ).joinToString("\n\n") { (h, t) -> "## $h\n$t" }
    }

    /** Splits on `^## ` headings; text before the first heading is dropped. */
    private fun parse(text: String): Map<String, String> =
        Regex("(?m)^## ").split(text).drop(1).associate {
            it.substringBefore('\n').trim() to it.substringAfter('\n', "").trim()
        }
}
