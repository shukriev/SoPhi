package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
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
/** Enough recent rounds for the model to stay coherent about what it was just doing. */
private const val COMPACTION_KEEP_RECENT_ROUNDS = 2
/**
 * Compacting twice in a row without dropping back under the threshold means summarisation itself
 * is no longer buying room — stop cleanly rather than loop on it. Mirrors Claude Code's own
 * auto-compact behaviour.
 */
private const val MAX_COMPACTIONS_WITHOUT_RELIEF = 2
private const val LOOP_GUARD_ROUND_BUDGET_MARGIN = 3
private val SEARCH_TOOL_NAMES = setOf("glob", "grep")
/** No tokenizer is available here, so this is a rough, provider-agnostic stand-in — good enough
 *  to keep a request roughly under budget without needing a real tokenizer per model. */
private const val CHARS_PER_TOKEN_ESTIMATE = 4
/**
 * Built-in tools (BashTool, FetchUrlTool) cap their own output, but a tool provided by an MCP
 * server — e.g. a browser snapshot — is outside our control and can return an arbitrarily large
 * result. A flat char cap can't be safe for every profile (100k chars is nothing against a 200k-
 * token Claude window but is most of a 32k-token local Ollama window), so this scales with the
 * actual configured window instead.
 */
private const val MAX_TOOL_RESULT_FRACTION_OF_WINDOW = 0.2

/**
 * Why a turn ended via [AgentLoop.finishEarly] rather than a normal completion. Callers (e.g.
 * PlanRunner) read this off the stopped-early entry's metadata to tell a capacity abort apart
 * from genuine success — before this existed, finishEarly's result was indistinguishable from a
 * normal turn at the call site, so a step cut short by e.g. compaction thrashing looked identical
 * to one that actually finished.
 */
enum class TurnStopReason { ToolRoundCeiling, LoopGuard, ContextExhausted, CompactionThrashing }

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
    /**
     * Total context window of the model this loop will call, in tokens. Required: there is
     * deliberately no per-model registry, because the caller who already picks `model` is the
     * only one who can say what that model's window is, and guessing wrong is worse than asking.
     */
    private val contextWindowTokens: Int,
    /** Tuning knob, not a per-model fact: compact once this fraction of the window is used. */
    private val compactionThreshold: Double = 0.8
) {
    /** Scaled to [contextWindowTokens] so no single tool result can claim more than
     *  [MAX_TOOL_RESULT_FRACTION_OF_WINDOW] of whatever window this loop's model actually has. */
    private fun truncateToolResult(result: String): String {
        val maxChars = (contextWindowTokens * MAX_TOOL_RESULT_FRACTION_OF_WINDOW * CHARS_PER_TOKEN_ESTIMATE).toInt()
        return if (result.length <= maxChars) result else result.take(maxChars) + "\n... output truncated"
    }

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
        stopReason: TurnStopReason,
        onEvent: suspend (TurnEvent) -> Unit
    ): AgentSession {
        val stopMessage = "[Stopped early: $reason]"
        onEvent(TurnEvent.Token(stopMessage))
        session.append(EntryRole.USER, userInput)
        pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
        session.append(EntryRole.ASSISTANT, stopMessage, mapOf("stopReason" to stopReason.name))
        sessionManager.save(session)
        return session
    }

    /**
     * Summarises this turn's older tool rounds in place, leaving the prior-session prefix and the
     * most recent [keepRecentRounds] rounds untouched. Returns false when there is nothing left to
     * compact (already at the floor) — the caller treats that as context exhaustion.
     *
     * Only ever called between rounds, never mid-round, and rounds are only ever appended whole
     * (ASSISTANT+toolCalls, then its TOOL results), so a cut can never split a tool call from its
     * results.
     */
    private suspend fun compactInPlace(
        messages: MutableList<Message>,
        turnStartIndex: Int,
        config: AgentConfig,
        keepRecentRounds: Int = COMPACTION_KEEP_RECENT_ROUNDS
    ): Boolean {
        val roundBoundaries = messages.indices
            .filter { i ->
                i >= turnStartIndex &&
                    messages[i].role == MessageRole.ASSISTANT &&
                    messages[i].toolCalls != null
            }
        if (roundBoundaries.size <= keepRecentRounds) return false

        val cutIndex = roundBoundaries[roundBoundaries.size - keepRecentRounds]
        val toCompact = messages.subList(turnStartIndex, cutIndex).toList()
        if (toCompact.isEmpty()) return false

        val summary = summarise(toCompact, config)
        messages.subList(turnStartIndex, cutIndex).clear()
        messages.add(
            turnStartIndex,
            Message(MessageRole.SYSTEM, "Earlier steps this turn, summarised:\n$summary")
        )
        return true
    }

    /** One provider.complete() call, same prompt shape ContextCompactor uses for cross-turn work. */
    private suspend fun summarise(toCompact: List<Message>, config: AgentConfig): String {
        val transcript = toCompact.joinToString("\n") { message ->
            val calls = message.toolCalls?.joinToString(", ") { "${it.name}(${it.argumentsJson})" }
            "${message.role.name}: ${message.content}" + (calls?.let { " [tool calls: $it]" } ?: "")
        }
        val request = CompletionRequest(
            messages = listOf(
                Message(
                    MessageRole.SYSTEM,
                    "Summarise the following steps of an agent's work concisely, preserving key " +
                        "facts, findings and decisions. Do not invent anything."
                ),
                Message(MessageRole.USER, transcript)
            ),
            model = config.model,
            maxTokens = 512,
            temperature = 0.3
        )
        return when (val response = provider.complete(request)) {
            is LLMResponse.Text -> response.content
            // A failed summarisation must not abort the turn — degrade to a truncated transcript.
            else -> toCompact.joinToString("; ") { "${it.role.name}: ${it.content.take(80)}" }
        }
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

        val compactionTriggerTokens = (contextWindowTokens * compactionThreshold).toInt()
        var compactionsWithoutRelief = 0
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
                    "reached the tool-round sanity ceiling (${config.maxToolRounds})",
                    TurnStopReason.ToolRoundCeiling, onEvent
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
                val preview = tool?.confirmationPreview(call.argumentsJson)
                call to ConfirmationRequest(call.id, call.name, call.argumentsJson, tier, preview)
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
                            Message(
                                role = MessageRole.TOOL,
                                content = truncateToolResult(result),
                                toolCallId = call.id,
                                toolName = call.name
                            ),
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
                return finishEarly(session, userInput, pendingRounds, guardReason, TurnStopReason.LoopGuard, onEvent)
            }

            // roundUsage reflects the request that *asked for* these tool calls — sent before any
            // of their results existed — so it alone can't see what was just appended. A round can
            // call several tools at once (e.g. a browsing task firing off a few browser_* calls
            // together), and their combined results can cross the budget even though none of them
            // individually hit truncateToolResult's cap. Add a rough estimate of what just landed
            // so the gate reacts to what's about to be sent next, not to stale pre-append usage.
            val projectedInputTokens = (roundUsage?.inputTokens ?: 0) +
                toolResults.sumOf { it.content.length } / CHARS_PER_TOKEN_ESTIMATE
            if (projectedInputTokens < compactionTriggerTokens) {
                // Relief can only ever be observed on a later round's usage, never on the same
                // value that just triggered compaction — so the reset lives here, not inline.
                compactionsWithoutRelief = 0
            } else {
                if (!compactInPlace(messages, turnStartIndex, config)) {
                    return finishEarly(
                        session, userInput, pendingRounds,
                        "context budget exhausted — a single round's output exceeds the compaction floor",
                        TurnStopReason.ContextExhausted, onEvent
                    )
                }
                compactionsWithoutRelief++
                if (compactionsWithoutRelief >= MAX_COMPACTIONS_WITHOUT_RELIEF) {
                    return finishEarly(
                        session, userInput, pendingRounds,
                        "compaction is thrashing — repeated summarisation isn't reducing context enough",
                        TurnStopReason.CompactionThrashing, onEvent
                    )
                }
            }
        }
    }
}
