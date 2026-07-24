package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.prompt.PromptBuilder
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.ToolRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@kotlinx.serialization.Serializable
private data class ToolCallRecord(val id: String, val name: String, val argumentsJson: String)

private data class PendingEntry(
    val role: EntryRole, val content: String, val metadata: Map<String, String>
)

private data class ToolCallOutcome(val call: ToolCall, val message: Message, val failed: Boolean)

private val entryJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private const val LOOP_GUARD_FAILURE_THRESHOLD = 3
private const val LOOP_GUARD_ROUND_BUDGET_MARGIN = 3
private val SEARCH_TOOL_NAMES = setOf("glob", "grep")

/**
 * Tracks the state a LoopGuardPolicy check needs across rounds of a single turn: how many
 * fully-failed rounds happened in a row, the narrowest path a glob/grep call has scoped into so
 * far, and whether the round-budget warning already fired once. One instance per turn() /
 * streamTurn() call — never shared or reused across turns.
 */
private class LoopGuardState(private val maxToolRounds: Int) {
    private var consecutiveFailedRounds = 0
    private var narrowestSearchPath: String? = null
    private var roundBudgetWarned = false

    private fun extractPathArg(argumentsJson: String): String? = runCatching {
        (entryJson.parseToJsonElement(argumentsJson).jsonObject["path"])?.jsonPrimitive?.content
    }.getOrNull()

    /** Call once per round, after that round's tool calls have all completed. */
    fun afterRound(outcomes: List<ToolCallOutcome>, roundAfterIncrement: Int): String? {
        val roundFullyFailed = outcomes.isNotEmpty() && outcomes.all { it.failed }
        consecutiveFailedRounds = if (roundFullyFailed) consecutiveFailedRounds + 1 else 0
        if (consecutiveFailedRounds >= LOOP_GUARD_FAILURE_THRESHOLD) {
            val streak = consecutiveFailedRounds
            consecutiveFailedRounds = 0
            return "$streak consecutive tool-call rounds failed in a row"
        }

        for (outcome in outcomes) {
            if (outcome.call.name !in SEARCH_TOOL_NAMES) continue
            val path = extractPathArg(outcome.call.argumentsJson)
            val previous = narrowestSearchPath
            if (previous != null && path == null) {
                narrowestSearchPath = null
                return "search scope broadened from \"$previous\" to the whole working directory"
            }
            if (narrowestSearchPath == null) narrowestSearchPath = path
        }

        if (!roundBudgetWarned && roundAfterIncrement >= maxToolRounds - LOOP_GUARD_ROUND_BUDGET_MARGIN) {
            roundBudgetWarned = true
            return "approaching the tool-round limit ($roundAfterIncrement/$maxToolRounds rounds used)"
        }

        return null
    }
}

class AgentLoop(
    private val provider: LLMProvider,
    private val registry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val compactor: ContextCompactor? = null,
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
    private val loopGuard: LoopGuardPolicy = LoopGuardPolicy.NEVER_CONTINUE
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
        val pendingRounds = mutableListOf<PendingEntry>()
        val loopGuardState = LoopGuardState(config.maxToolRounds)

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
                    pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
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
                    pendingRounds.add(PendingEntry(
                        EntryRole.ASSISTANT, "",
                        mapOf(
                            "replay" to "false",
                            "toolCalls" to entryJson.encodeToString(
                                response.calls.map { ToolCallRecord(it.id, it.name, it.argumentsJson) })
                        )
                    ))

                    val allowedCalls = response.calls.map { call ->
                        val tool = registry.getOrNull(call.name)
                        val allowed = tool == null || tool.riskLevel == RiskLevel.SAFE ||
                            confirmationPolicy.confirm(call.name, call.argumentsJson)
                        call to allowed
                    }

                    val toolOutcomes = coroutineScope {
                        allowedCalls.map { (call, allowed) ->
                            async {
                                onEvent(TurnEvent.ToolCallStarted(call.name, call.argumentsJson))
                                val start = System.currentTimeMillis()
                                var failed = false
                                val result = if (!allowed) {
                                    failed = true
                                    "Error: Tool '${call.name}' execution denied by confirmation policy"
                                } else {
                                    registry.getOrNull(call.name)
                                        ?.let { tool ->
                                            runCatching { tool.execute(call.argumentsJson) }
                                                .getOrElse { e -> failed = true; "Error: ${e.message}" }
                                        }
                                        ?: run { failed = true; "Error: Tool '${call.name}' not found" }
                                }
                                onEvent(TurnEvent.ToolCallFinished(call.name, result, failed, System.currentTimeMillis() - start))
                                ToolCallOutcome(
                                    call,
                                    Message(role = MessageRole.TOOL, content = result, toolCallId = call.id, toolName = call.name),
                                    failed
                                )
                            }
                        }.awaitAll()
                    }
                    val toolResults = toolOutcomes.map { it.message }
                    messages.addAll(toolResults)
                    toolResults.forEach { m ->
                        pendingRounds.add(PendingEntry(
                            EntryRole.TOOL_RESULT, m.content,
                            mapOf("replay" to "false", "toolCallId" to (m.toolCallId ?: ""), "toolName" to (m.toolName ?: ""))
                        ))
                    }
                    toolRound++

                    val guardReason = loopGuardState.afterRound(toolOutcomes, toolRound)
                    if (guardReason != null && !loopGuard.askToContinue(guardReason)) {
                        val stopMessage = "[Stopped early: $guardReason]"
                        onEvent(TurnEvent.Token(stopMessage))
                        session.append(EntryRole.USER, userInput)
                        pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
                        session.append(EntryRole.ASSISTANT, stopMessage)
                        sessionManager.save(session)
                        return session
                    }
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
        val messages = PromptBuilder.build(session.branch()).toMutableList()
        messages.add(Message(MessageRole.USER, userInput))

        var toolRound = 0
        val pendingRounds = mutableListOf<PendingEntry>()
        val loopGuardState = LoopGuardState(config.maxToolRounds)

        while (true) {
            val request = CompletionRequest(
                messages = messages.toList(),
                model = config.model,
                maxTokens = config.maxTokens,
                temperature = config.temperature,
                systemPrompt = config.systemPrompt,
                tools = registry.definitions()
            )

            val contentBuf = StringBuilder()
            var pendingToolCalls: List<ToolCall>? = null
            provider.stream(request).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        contentBuf.append(event.text)
                        onEvent(TurnEvent.Token(event.text))
                    }
                    is StreamEvent.Reasoning -> onEvent(TurnEvent.ReasoningToken(event.text))
                    is StreamEvent.ToolCallsReady -> pendingToolCalls = event.calls
                }
            }

            val toolCalls = pendingToolCalls
            if (toolCalls == null) {
                session.append(EntryRole.USER, userInput)
                pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
                session.append(EntryRole.ASSISTANT, contentBuf.toString())
                sessionManager.save(session)

                return if (compactor != null && session.branch().size > config.maxBranchLength) {
                    compactor.compact(session, config).also { sessionManager.save(it) }
                } else {
                    session
                }
            }

            if (toolRound >= config.maxToolRounds) {
                throw IllegalStateException("Max tool rounds (${config.maxToolRounds}) exceeded")
            }
            messages.add(Message(MessageRole.ASSISTANT, content = "", toolCalls = toolCalls))
            pendingRounds.add(PendingEntry(
                EntryRole.ASSISTANT, "",
                mapOf(
                    "replay" to "false",
                    "toolCalls" to entryJson.encodeToString(
                        toolCalls.map { ToolCallRecord(it.id, it.name, it.argumentsJson) })
                )
            ))

            val allowedCalls = toolCalls.map { call ->
                val tool = registry.getOrNull(call.name)
                val allowed = tool == null || tool.riskLevel == RiskLevel.SAFE ||
                    confirmationPolicy.confirm(call.name, call.argumentsJson)
                call to allowed
            }

            val toolOutcomes = coroutineScope {
                allowedCalls.map { (call, allowed) ->
                    async {
                        onEvent(TurnEvent.ToolCallStarted(call.name, call.argumentsJson))
                        val start = System.currentTimeMillis()
                        var failed = false
                        val result = if (!allowed) {
                            failed = true
                            "Error: Tool '${call.name}' execution denied by confirmation policy"
                        } else {
                            registry.getOrNull(call.name)
                                ?.let { tool ->
                                    runCatching { tool.execute(call.argumentsJson) }
                                        .getOrElse { e -> failed = true; "Error: ${e.message}" }
                                }
                                ?: run { failed = true; "Error: Tool '${call.name}' not found" }
                        }
                        onEvent(TurnEvent.ToolCallFinished(call.name, result, failed, System.currentTimeMillis() - start))
                        ToolCallOutcome(
                            call,
                            Message(role = MessageRole.TOOL, content = result, toolCallId = call.id, toolName = call.name),
                            failed
                        )
                    }
                }.awaitAll()
            }
            val toolResults = toolOutcomes.map { it.message }
            messages.addAll(toolResults)
            toolResults.forEach { m ->
                pendingRounds.add(PendingEntry(
                    EntryRole.TOOL_RESULT, m.content,
                    mapOf("replay" to "false", "toolCallId" to (m.toolCallId ?: ""), "toolName" to (m.toolName ?: ""))
                ))
            }
            toolRound++

            val guardReason = loopGuardState.afterRound(toolOutcomes, toolRound)
            if (guardReason != null && !loopGuard.askToContinue(guardReason)) {
                val stopMessage = "[Stopped early: $guardReason]"
                onEvent(TurnEvent.Token(stopMessage))
                session.append(EntryRole.USER, userInput)
                pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
                session.append(EntryRole.ASSISTANT, stopMessage)
                sessionManager.save(session)
                return session
            }
        }
    }
}
