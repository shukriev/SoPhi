package dev.sophi.core.context

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole

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

        val compacted = AgentSession(id = session.id, title = session.title, parentSessionId = session.parentSessionId)
        compacted.append(
            role = EntryRole.SYSTEM,
            content = "Previous conversation summary:\n$summary",
            metadata = mapOf("_compacted" to "true")
        )
        toKeep.forEach { entry ->
            compacted.append(entry.role, entry.content, entry.metadata)
        }
        return compacted
    }
}
