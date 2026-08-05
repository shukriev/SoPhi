package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.prompt.PromptBuilder
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
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
    private val grants: Set<String> = emptySet(),
    private val loopGuard: LoopGuardPolicy = LoopGuardPolicy.NEVER_CONTINUE,
    // TEMPORARY default — removed in the final wiring task, after every call site passes a real
    // value. There is deliberately no per-model context-window registry: the caller who already
    // picks `model` is the only one who can say what that model's window is.
    private val contextWindowTokens: Int = 200_000,
    /** Tuning knob, not a per-model fact: compact once this fraction of the window is used. */
    private val compactionThreshold: Double = 0.8
) {
    /**
     * The one way a turn ends early. Emits the stop message as a token, persists this turn's
     * user input and every round accumulated so far, records why it stopped, and saves.
     * Every early-stop path (loop guard, tool-round ceiling, context exhaustion, compaction
     * thrashing) goes through here so none of them can ever silently drop work again.
     */
    private suspend fun finishEarly(
        session: AgentSession,
        userInput: String,
        pendingRounds: List<PendingEntry>,
        reason: String,
        onEvent: suspend (TurnEvent) -> Unit
    ): AgentSession {
        val stopMessage = "[Stopped early: $reason]"
        onEvent(TurnEvent.Token(stopMessage))
        session.append(EntryRole.USER, userInput)
        pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
        session.append(EntryRole.ASSISTANT, stopMessage)
        sessionManager.save(session)
        return session
    }

    suspend fun turn(
        session: AgentSession,
        userInput: String,
        config: AgentConfig,
        onEvent: suspend (TurnEvent) -> Unit = {}
    ): AgentSession = streamTurn(session, userInput, config, onEvent)

    suspend fun streamTurn(
        session: AgentSession,
        userInput: String,
        config: AgentConfig,
        onEvent: suspend (TurnEvent) -> Unit
    ): AgentSession {
        val messages = PromptBuilder.build(session.branch()).toMutableList()
        messages.add(Message(MessageRole.USER, userInput))
        // Everything from here on is this turn's own accumulated rounds — the only region
        // mid-loop compaction is ever allowed to touch. The prior-session prefix in front of it
        // is governed separately by cross-turn compaction (AgentConfig.maxBranchLength).
        val turnStartIndex = messages.size

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
            var roundUsage: TokenUsage? = null
            provider.stream(request).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        contentBuf.append(event.text)
                        onEvent(TurnEvent.Token(event.text))
                    }
                    is StreamEvent.Reasoning -> onEvent(TurnEvent.ReasoningToken(event.text))
                    is StreamEvent.ToolCallsReady -> pendingToolCalls = event.calls
                    // Cumulative for the whole prompt just sent, so the latest value IS the total.
                    is StreamEvent.Usage -> roundUsage = event.usage
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
                return finishEarly(
                    session, userInput, pendingRounds,
                    "reached the tool-round sanity ceiling (${config.maxToolRounds})", onEvent
                )
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

            val classified = toolCalls.map { call ->
                val tool = registry.getOrNull(call.name)
                val tier = tool?.riskLevel(call.argumentsJson) ?: RiskLevel.SAFE
                call to ConfirmationRequest(call.id, call.name, call.argumentsJson, tier)
            }
            val needsDecision = classified.filter { (_, req) ->
                req.riskLevel != RiskLevel.SAFE && req.toolName !in grants
            }
            val decisions = if (needsDecision.isEmpty()) {
                emptyMap()
            } else {
                val requests = needsDecision.map { it.second }
                onEvent(TurnEvent.ConfirmationStarted(requests.map { it.toolName }))
                try {
                    confirmationPolicy.confirm(requests)
                } finally {
                    onEvent(TurnEvent.ConfirmationFinished)
                }
            }
            val allowedCalls = classified.map { (call, req) ->
                val allowed = req.riskLevel == RiskLevel.SAFE ||
                    req.toolName in grants ||
                    decisions[req.callId] == true
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
                        // Tools signal a handled failure by returning an "Error: "-prefixed string
                        // just as often as by throwing (every built-in tool's own validation follows
                        // this convention) — count both the same way for loop-guard/reliability purposes.
                        failed = failed || result.startsWith("Error: ")
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
                return finishEarly(session, userInput, pendingRounds, guardReason, onEvent)
            }
        }
    }
}
