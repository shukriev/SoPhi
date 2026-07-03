package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.prompt.PromptBuilder
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect

class AgentLoop(
    private val provider: LLMProvider,
    private val registry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val compactor: ContextCompactor? = null
) {
    suspend fun turn(
        session: AgentSession,
        userInput: String,
        config: AgentConfig,
        onEvent: suspend (TurnEvent) -> Unit = {}
    ): AgentSession {
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
                    onEvent(TurnEvent.Token(response.content))
                    session.append(EntryRole.USER, userInput)
                    session.append(EntryRole.ASSISTANT, response.content)
                    sessionManager.save(session)

                    return if (compactor != null && session.branch().size > config.maxBranchLength) {
                        compactor.compact(session, config).also { sessionManager.save(it) }
                    } else {
                        session
                    }
                }
                is LLMResponse.ToolUse -> {
                    if (toolRound >= config.maxToolRounds) {
                        throw IllegalStateException("Max tool rounds (${config.maxToolRounds}) exceeded")
                    }
                    messages.add(Message(MessageRole.ASSISTANT, content = "", toolCalls = response.calls))

                    val toolResults = coroutineScope {
                        response.calls.map { call ->
                            async {
                                onEvent(TurnEvent.ToolCallStarted(call.name, call.argumentsJson))
                                val result = registry.getOrNull(call.name)
                                    ?.let { tool ->
                                        runCatching { tool.execute(call.argumentsJson) }
                                            .getOrElse { e -> "Error: ${e.message}" }
                                    }
                                    ?: "Error: Tool '${call.name}' not found"
                                onEvent(TurnEvent.ToolCallFinished(call.name, result))
                                Message(
                                    role = MessageRole.TOOL,
                                    content = result,
                                    toolCallId = call.id,
                                    toolName = call.name
                                )
                            }
                        }.awaitAll()
                    }
                    messages.addAll(toolResults)
                    toolRound++
                }
                is LLMResponse.Error -> {
                    throw IllegalStateException("LLM error: ${response.message}", response.cause)
                }
            }
        }
    }

    suspend fun streamTurn(
        session: AgentSession,
        userInput: String,
        config: AgentConfig,
        onEvent: suspend (TurnEvent) -> Unit
    ): AgentSession {
        val messages = buildList {
            addAll(PromptBuilder.build(session.branch()))
            add(Message(MessageRole.USER, userInput))
        }
        val request = CompletionRequest(
            messages = messages,
            model = config.model,
            maxTokens = config.maxTokens,
            temperature = config.temperature,
            systemPrompt = config.systemPrompt,
            tools = registry.definitions()
        )
        val buf = StringBuilder()
        var streamCompleted = false
        try {
            provider.stream(request).collect { token ->
                buf.append(token)
                onEvent(TurnEvent.Token(token))
            }
            streamCompleted = true
        } catch (e: Exception) { /* stream threw — fall through to turn() */ }

        return if (streamCompleted && buf.isNotEmpty()) {
            session.append(EntryRole.USER, userInput)
            session.append(EntryRole.ASSISTANT, buf.toString())
            sessionManager.save(session)
            if (compactor != null && session.branch().size > config.maxBranchLength)
                compactor.compact(session, config).also { sessionManager.save(it) }
            else session
        } else {
            turn(session, userInput, config, onEvent)
        }
    }
}
