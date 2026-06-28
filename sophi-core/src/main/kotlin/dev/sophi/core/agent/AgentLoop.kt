package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.prompt.PromptBuilder
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry

class AgentLoop(
    private val provider: LLMProvider,
    private val registry: ToolRegistry,
    private val sessionManager: SessionManager
) {
    suspend fun turn(session: AgentSession, userInput: String, config: AgentConfig): AgentSession {
        val messages = PromptBuilder.build(session.branch()).toMutableList()
        messages.add(Message(MessageRole.USER, userInput))

        var toolRound = 0

        while (true) {
            val request = CompletionRequest(
                messages = messages.toList(),
                model = config.model,
                maxTokens = config.maxTokens,
                temperature = config.temperature,
                systemPrompt = config.systemPrompt,
                tools = registry.definitions()
            )

            when (val response = provider.complete(request)) {
                is LLMResponse.Text -> {
                    session.append(EntryRole.USER, userInput)
                    session.append(EntryRole.ASSISTANT, response.content)
                    sessionManager.save(session)
                    return session
                }
                is LLMResponse.ToolUse -> {
                    if (toolRound >= config.maxToolRounds) {
                        throw IllegalStateException("Max tool rounds (${config.maxToolRounds}) exceeded")
                    }
                    // Tool dispatch added in Task 4
                    throw IllegalStateException("Tool use not yet implemented")
                }
                is LLMResponse.Error -> {
                    throw IllegalStateException("LLM error: ${response.message}", response.cause)
                }
            }
        }
    }
}
