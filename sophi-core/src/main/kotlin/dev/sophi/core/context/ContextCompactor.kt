package dev.sophi.core.context

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import java.util.UUID

class ContextCompactor(private val provider: LLMProvider) {

    suspend fun compact(
        session: AgentSession,
        config: AgentConfig,
        keepRecentCount: Int = 4
    ): AgentSession {
        val entries = session.branch()
        if (entries.size <= keepRecentCount) return session

        val toCompact = entries.dropLast(keepRecentCount)
        val toKeep = entries.takeLast(keepRecentCount)

        val summaryPrompt = toCompact.joinToString("\n") { entry ->
            "${entry.role.name}: ${entry.content}"
        }
        val summaryRequest = CompletionRequest(
            messages = listOf(
                Message(MessageRole.SYSTEM, "Summarise the following conversation concisely, preserving key facts and decisions."),
                Message(MessageRole.USER, summaryPrompt)
            ),
            model = config.model,
            maxTokens = 512,
            temperature = 0.3
        )

        val summary = when (val response = provider.complete(summaryRequest)) {
            is LLMResponse.Text -> response.content
            else -> toCompact.joinToString("; ") { "${it.role.name}: ${it.content.take(80)}" }
        }

        // Replace only the compacted prefix of the active branch: the summary becomes a new
        // root and the first kept entry is re-parented onto it. Entries on other branches
        // (and the compacted prefix itself) stay in the DAG untouched.
        val summaryEntry = SessionEntry(
            id = UUID.randomUUID().toString(),
            parentId = null,
            role = EntryRole.SYSTEM,
            content = "Previous conversation summary:\n$summary",
            timestamp = System.currentTimeMillis(),
            metadata = mapOf("_compacted" to "true")
        )
        val firstKeptId = toKeep.first().id
        val rewritten = session.entries.map { entry ->
            if (entry.id == firstKeptId) entry.copy(parentId = summaryEntry.id) else entry
        }
        return AgentSession(
            id = session.id,
            title = session.title,
            parentSessionId = session.parentSessionId,
            initialEntries = listOf(summaryEntry) + rewritten,
            initialTipId = session.tip?.id
        )
    }
}
