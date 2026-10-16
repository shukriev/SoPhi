package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A model's title reply as one clean line: no quotes, label, markdown or trailing period; ≤ 60 chars. */
internal fun cleanTitle(raw: String?): String? {
    val line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    val t = line.removePrefix("Title:").removePrefix("title:").trim()
        .trim('"', '\'', '*', '`', '“', '”', ' ').trimEnd('.').trim()
    return t.take(60).trim().takeIf { it.isNotEmpty() }
}

/**
 * Names an untitled session after its first completed turn (RuntimeBuilder.autoTitleSessions): one short,
 * no-reasoning model call in the background, so the reply is never delayed. A session that already has a
 * title (created with one, or renamed by the user) is never touched; a failed call leaves it untitled.
 */
internal class SessionTitlePlugin(
    private val provider: LLMProvider,
    private val model: String,
    private val sessionManager: SessionManager,
    private val onTitled: (sessionId: String, title: String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SophiPlugin {
    override val name = "session-titles"

    override fun hooks(): List<AgentHook> = listOf(object : AgentHook {
        override val point = HookPoint.AFTER_TURN
        override suspend fun invoke(context: HookContext) {
            if (context.ambient) return
            val input = context.userInput ?: return
            val session = runCatching { sessionManager.load(context.sessionId) }.getOrNull() ?: return
            if (session.title != null || session.branch().count { it.role == EntryRole.USER } != 1) return
            val reply = context.assistantReply.orEmpty()
            scope.launch {
                val title = runCatching { generate(input, reply) }.getOrNull() ?: return@launch
                // Re-check: the user may have renamed it while the call ran.
                if (runCatching { sessionManager.load(context.sessionId).title }.getOrNull() != null) return@launch
                runCatching { sessionManager.rename(context.sessionId, title) }.onSuccess { onTitled(context.sessionId, title) }
            }
        }
    })

    private suspend fun generate(input: String, reply: String): String? {
        val prompt = buildString {
            appendLine("Write a short title (3 to 6 words) for this conversation, in its language.")
            appendLine("Reply with the title only: no quotes, no punctuation at the end.")
            appendLine(); appendLine("User: ${input.take(800)}")
            if (reply.isNotBlank()) appendLine("Assistant: ${reply.take(400)}")
        }
        val r = provider.complete(CompletionRequest(
            listOf(Message(MessageRole.USER, prompt)), model, maxTokens = 32, temperature = 0.2,
            // A thinking model would otherwise spend the small budget reasoning and return nothing.
            reasoningEffort = "none"))
        return cleanTitle((r as? LLMResponse.Text)?.content)
    }
}
