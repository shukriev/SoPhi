package dev.sophi.context

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.system1.Judgments
import kotlin.coroutines.cancellation.CancellationException

private const val PROMPT = "Summarise what this file is for in one or two sentences, in the words a person would use when " +
    "asking about it: its purpose, behaviour and key concepts. No code, no file name, no preamble."

/** LLM file summaries for retrieval (spec §5.2 step 5; E2 decides whether they ship). A small judgment: no reasoning. */
class FileSummariser(private val provider: LLMProvider, private val model: String) {
    suspend fun summarise(path: String, text: String): String? {
        val request = CompletionRequest(
            messages = listOf(Message(MessageRole.SYSTEM, PROMPT), Message(MessageRole.USER, "Path: $path\n\n${text.take(6_000)}")),
            model = model, maxTokens = 160, temperature = 0.2, reasoningEffort = "none", judgment = Judgments.CONTEXT_SUMMARY)
        val out = try { (provider.complete(request) as? LLMResponse.Text)?.content } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return out?.replace(Regex("(?s)<think>.*?</think>"), "")?.let(::cleanPromptText)?.take(400)?.takeIf { it.isNotBlank() }
    }
}
