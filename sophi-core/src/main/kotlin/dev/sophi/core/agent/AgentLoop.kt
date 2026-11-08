package dev.sophi.core.agent

import dev.sophi.ai.api.BudgetExceededException
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.plan.DecomposeGoalTool
import dev.sophi.core.audit.ActorContext
import dev.sophi.core.audit.AuditScope
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.audit.DecisionNotes
import dev.sophi.core.audit.PERSON_DECISIONS
import dev.sophi.core.audit.LOCAL_ACTOR
import dev.sophi.core.audit.auditEntry
import dev.sophi.core.audit.recordQuietly
import dev.sophi.core.context.CompactionMode
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.context.Elider
import dev.sophi.core.context.Summariser
import dev.sophi.core.context.WorkingSet
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.permissions.PermissionScope
import dev.sophi.core.prompt.PromptBuilder
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.DenialLimit
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.ActivatedTools
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.GateMode
import dev.sophi.core.tools.GuardianScope
import dev.sophi.core.tools.READ_CHECK_LIMIT
import dev.sophi.core.tools.ReadVerdict
import dev.sophi.core.tools.GuardianVerdict
import dev.sophi.core.tools.LlmGuardian
import dev.sophi.core.tools.Unattended
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanRecorder
import dev.sophi.core.dryrun.planStepStub
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.tools.DEFAULT_RESULT_PAGE_CHARS
import dev.sophi.core.tools.RESULT_READ_NAME
import dev.sophi.core.tools.ResultReadTool
import dev.sophi.core.tools.limitResult
import dev.sophi.core.tools.callEffects
import dev.sophi.core.tools.EffectKind
import dev.sophi.core.tools.fenceAsData
import dev.sophi.core.tools.isToolError
import dev.sophi.core.undo.CheckpointStore
import dev.sophi.core.undo.RoundChanges
import dev.sophi.core.tools.currentTodos
import dev.sophi.core.tools.renderTodos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext

@kotlinx.serialization.Serializable
private data class ToolCallRecord(val id: String, val name: String, val argumentsJson: String)

private data class PendingEntry(
    val role: EntryRole, val content: String, val metadata: Map<String, String>
)

/** One turn's rounds so far, and whether they were already written to the session. */
/** [images]: the turn's input images, saved on its USER entry whatever the model can see. */
private class TurnProgress(val images: List<dev.sophi.ai.api.ImageRef> = emptyList()) {
    val pendingRounds = mutableListOf<PendingEntry>()
    var persisted = false
}

private data class ToolCallOutcome(val call: ToolCall, val message: Message, val failed: Boolean)

private val entryJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Null when [argumentsJson] is blank or a JSON object; otherwise an error the model can act on. */
internal fun invalidArgumentsError(toolName: String, argumentsJson: String): String? {
    if (argumentsJson.isBlank()) return null
    val reason = runCatching { entryJson.parseToJsonElement(argumentsJson).jsonObject }.exceptionOrNull()?.message
        ?: return null
    return "Error: invalid arguments: $reason. Call $toolName again with a single valid JSON object as its arguments."
}

private const val LOOP_GUARD_FAILURE_THRESHOLD = 3
private const val LOOP_GUARD_BROADENING_THRESHOLD = 2
/** From this many identical calls (same tool, same arguments) in one turn, the result carries a note. */
private const val REPEAT_NOTE_THRESHOLD = 3
/** ...and at this many the loop guard steps in. Seen live: a browser_navigate to one URL 29 times in a turn. */
private const val LOOP_GUARD_REPEAT_THRESHOLD = 6
/** Enough recent rounds for the model to stay coherent about what it was just doing. */
private const val COMPACTION_KEEP_RECENT_ROUNDS = 2
private const val SUMMARY_MARKER = "\n\n[Earlier steps this turn, summarised]\n"
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
 * The most of the window one result's page may take. The page is the configured one (8,000 chars by default) unless
 * this is smaller: on a small local window 8,000 chars could still be most of it.
 */
private const val MAX_TOOL_RESULT_FRACTION_OF_WINDOW = 0.2

/**
 * Why a turn ended via [AgentLoop.finishEarly] rather than a normal completion. Callers (e.g.
 * PlanRunner) read this off the stopped-early entry's metadata to tell a capacity abort apart
 * from genuine success — before this existed, finishEarly's result was indistinguishable from a
 * normal turn at the call site, so a step cut short by e.g. compaction thrashing looked identical
 * to one that actually finished.
 */
enum class TurnStopReason {
    ToolRoundCeiling, LoopGuard, ContextExhausted, CompactionThrashing, EmptyResponse, OutputTruncated, UserStopped, Error, BudgetExceeded,
    /** An unattended run hit [dev.sophi.core.tools.DenialLimit]: nobody could approve what it kept asking for. */
    DeniedUnattended
}

/** OpenAI's finish_reason for "ran out of output tokens", shared by every compatible server. */
private const val FINISH_REASON_LENGTH = "length"

/**
 * Tracks the state a LoopGuardPolicy check needs across rounds of a single turn: how many
 * fully-failed rounds happened in a row, the narrowest path a glob/grep call has scoped into so
 * far, and whether the round-budget warning already fired once. One instance per turn() /
 * streamTurn() call — never shared or reused across turns.
 */
private class LoopGuardState(private val maxToolRounds: Int) {
    private var consecutiveFailedRounds = 0
    private var narrowestSearchPath: String? = null
    private var searchesBeyondScope = 0
    private var roundBudgetWarned = false
    private val repeatCounts = mutableMapOf<String, Int>()
    private val totalCounts = mutableMapOf<String, Int>() // never forgiven: a call elided again and again still adds up
    private var repeatedTooOften: Pair<String, Int>? = null

    private fun repeatKey(name: String, argumentsJson: String) = name + "\u0000" + argumentsJson.trim()

    /** An elided result invites one re-read: restart that call's count so only a real loop afterwards trips the guard. */
    fun forgive(name: String, argumentsJson: String) {
        repeatCounts.remove(repeatKey(name, argumentsJson))
    }

    /** Counts this call among identical ones (same tool, same arguments) this turn; returns the count. */
    fun countRepeat(name: String, argumentsJson: String): Int {
        val key = repeatKey(name, argumentsJson)
        val n = (repeatCounts[key] ?: 0) + 1
        repeatCounts[key] = n
        val total = (totalCounts[key] ?: 0) + 1
        totalCounts[key] = total
        if (n >= LOOP_GUARD_REPEAT_THRESHOLD) {
            repeatedTooOften = name to n
            repeatCounts[key] = 0 // a policy that says "continue" isn't asked again until 6 more
        } else if (total >= 2 * LOOP_GUARD_REPEAT_THRESHOLD) {
            repeatedTooOften = name to total
        }
        if (total >= 2 * LOOP_GUARD_REPEAT_THRESHOLD) totalCounts[key] = 0
        return n
    }

    // The directory a search is confined to: its `path` joined with a glob pattern's literal leading
    // directories (a pattern starting "ledger/" then wildcards is scoped to ledger; one starting with
    // a wildcard, to nothing). Null = the whole working directory. Reading `path` alone mistook a
    // re-scoped pattern for a broadened search. (Line comments: glob text would nest a block comment.)
    private fun searchScope(name: String, argumentsJson: String): String? = runCatching {
        val args = entryJson.parseToJsonElement(argumentsJson).jsonObject
        val path = args["path"]?.jsonPrimitive?.content.orEmpty().trim('/')
        val pattern = if (name == "glob") args["pattern"]?.jsonPrimitive?.content.orEmpty() else ""
        val literalDirs = pattern.split('/').dropLast(1).takeWhile { seg -> seg.none { it in "*?[{" } }
        (listOf(path) + literalDirs).filter { it.isNotEmpty() && it != "." }.joinToString("/").ifEmpty { null }
    }.getOrNull()

    /** Call once per round, after that round's tool calls have all completed. */
    fun afterRound(outcomes: List<ToolCallOutcome>, roundAfterIncrement: Int): String? {
        repeatedTooOften?.let { (name, n) ->
            repeatedTooOften = null
            return "the same call ($name, identical arguments) was made $n times this turn"
        }
        val roundFullyFailed = outcomes.isNotEmpty() && outcomes.all { it.failed }
        consecutiveFailedRounds = if (roundFullyFailed) consecutiveFailedRounds + 1 else 0
        if (consecutiveFailedRounds >= LOOP_GUARD_FAILURE_THRESHOLD) {
            val streak = consecutiveFailedRounds
            consecutiveFailedRounds = 0
            return "$streak consecutive tool-call rounds failed in a row"
        }

        for (outcome in outcomes) {
            // A failed call (e.g. arguments that didn't parse) searched nothing, so it scopes nothing.
            if (outcome.call.name !in SEARCH_TOOL_NAMES || outcome.failed) continue
            val path = searchScope(outcome.call.name, outcome.call.argumentsJson)
            val previous = narrowestSearchPath
            // One widening after a scoped search came up empty is recovery (a wrong path, say); the
            // flailing this guards against is searching everything again and again, so the scope is
            // kept and only the second search beyond it stops the turn.
            if (previous != null && path == null && ++searchesBeyondScope >= LOOP_GUARD_BROADENING_THRESHOLD) {
                searchesBeyondScope = 0
                return "search scope broadened from \"$previous\" to the whole working directory, again"
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
     * A host that switches model mid-session sets it; each turn reads it once, at its start.
     */
    @Volatile var contextWindowTokens: Int,
    /** Tuning knob, not a per-model fact: compact once this fraction of the window is used. */
    private val compactionThreshold: Double = 0.8,
    /** Called once per model request with that request's usage and kind ("turn" | "summarise" | "elide"); failures never reach the loop. */
    private val onUsage: (TokenUsage, String) -> Unit = { _, _ -> },
    /** How mid-turn compaction works: elide old tool results first (ELIDE/STRUCTURED), then summarise. */
    private val compaction: CompactionMode = CompactionMode.STRUCTURED,
    /** Snapshots the workspace before each round with a DESTRUCTIVE call, so /undo can restore it. Null = no checkpoints. */
    private val checkpoints: CheckpointStore? = null,
    /** Gets one entry per tool call. Null = the enclosing loop's sink (AuditScope), if any. */
    private val audit: AuditSink? = null,
    /** Allow/ask/deny rules checked before the confirmation flow. Null = the enclosing loop's rules (PermissionScope), if any. */
    private val permissions: PermissionRules? = null,
    /** Whether the model this loop calls can see images; asked for every request, so a host's model switch applies at once. */
    private val supportsImages: () -> Boolean = { false },
    /** Plugin hooks fired around tools, requests, confirmations and compaction. Null = the enclosing loop's (HookScope), if any. */
    private val hooks: LoopHooks? = null,
    /** Reviews DESTRUCTIVE calls about to run that no person approved. Null = the enclosing loop's (GuardianScope), if any. */
    private val guardian: LlmGuardian? = null,
) {
    private val summariser = Summariser(provider, compaction, contextWindowTokens, dev.sophi.ai.system1.Judgments.LOOP_SUMMARISE)

    /**
     * Writes [req]'s audit entry. Never fails the call (see [recordQuietly]). Not suspending,
     * so a cancelled call can still be recorded on its way out.
     */
    // The session the user talks to: a subagent's or plan step's session never gets a next turn to receive a release.
    private fun topSessionId(session: AgentSession): String {
        var s = session
        repeat(8) { s = s.parentSessionId?.let { id -> runCatching { sessionManager.load(id) }.getOrNull() } ?: return s.id }
        return s.id
    }

    private fun recordAudit(
        sink: AuditSink, actor: ActorContext?, session: AgentSession, req: ConfirmationRequest,
        effect: Effect?, notes: DecisionNotes, outcome: String, result: String, start: Long, ruleOverride: String? = null,
        plan: String? = null, guardianVerdict: String? = null, readCheck: String? = null, readCheckMs: Long? = null, resultId: String? = null,
    ) = sink.recordQuietly {
        val rule = ruleOverride ?: when {
            effect == Effect.DENY -> "rule:deny"
            effect == Effect.ALLOW -> "rule:allow"
            effect == Effect.ASK -> notes.rules[req.callId] ?: "policy"
            req.riskLevel == RiskLevel.SAFE -> "safe"
            req.toolName in grants -> "grant"
            else -> notes.rules[req.callId] ?: "policy"
        }
        auditEntry(actor?.actorId ?: LOCAL_ACTOR, actor?.tenantId, session.id, req.toolName, req.riskLevel,
            req.argumentsJson, rule, outcome, result, start, plan, guardianVerdict, readCheck, readCheckMs, resultId,
            registry.getOrNull(req.toolName)?.callEffects(req.argumentsJson).orEmpty())
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
        turn: TurnProgress,
        reason: String,
        stopReason: TurnStopReason,
        onEvent: suspend (TurnEvent) -> Unit
    ): AgentSession {
        val stopMessage = "[Stopped early: $reason]"
        onEvent(TurnEvent.Token(stopMessage))
        turn.persisted = true
        session.append(EntryRole.USER, userInput, dev.sophi.core.session.imagesMetadata(turn.images))
        turn.pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
        session.append(EntryRole.ASSISTANT, stopMessage, mapOf("stopReason" to stopReason.name))
        sessionManager.save(session)
        return session
    }

    /** A round's `moves`, and its (and its subagents') allowed bash commands that reach outside the workspace (`outsideCommands`). */
    private fun undoRecord(changes: RoundChanges): Map<String, String> = buildMap {
        if (changes.moves.isNotEmpty()) put("moves", entryJson.encodeToString(changes.moves))
        val outside = checkpoints?.root?.let(changes::outsideCommands).orEmpty()
        if (outside.isNotEmpty()) put("outsideCommands", entryJson.encodeToString(outside))
    }

    private fun stubbedToolCallIds(messages: List<Message>): Set<String> =
        messages.filter { it.role == MessageRole.TOOL && it.content.startsWith(Elider.PREFIX) }
            .mapNotNull { it.toolCallId }.toSet()

    /**
     * Relieves context pressure from this turn's own rounds, leaving the prior-session prefix and the
     * most recent [keepRecentRounds] rounds untouched. First elides old tool results (no LLM call,
     * when the mode allows); only if nothing was left to elide does it summarise, folding the older
     * rounds into the turn's user message (never a mid-list SYSTEM message, which strict chat
     * templates reject). Returns false when there is nothing left to compact (already at the floor) —
     * the caller treats that as context exhaustion.
     *
     * Only ever called between rounds, never mid-round, and rounds are only ever appended whole
     * (ASSISTANT+toolCalls, then its TOOL results), so a cut can never split a tool call from its
     * results.
     */
    private suspend fun compactInPlace(
        messages: MutableList<Message>,
        turnStartIndex: Int,
        config: AgentConfig,
        /** This turn's user message as sent before any summary was folded in. */
        turnUserContent: String,
        /** Raw, unelided rounds of the whole turn: the source of the summary's exact facts. */
        rounds: List<PendingEntry>,
        projectedInputTokens: Int,
        triggerTokens: Int,
        keepRecentRounds: Int = COMPACTION_KEEP_RECENT_ROUNDS
    ): Boolean {
        val roundBoundaries = messages.indices
            .filter { i ->
                i >= turnStartIndex &&
                    messages[i].role == MessageRole.ASSISTANT &&
                    messages[i].toolCalls != null
            }
        if (roundBoundaries.size <= keepRecentRounds) return elideLargestRelief(messages, turnStartIndex, projectedInputTokens, triggerTokens)

        var projected = projectedInputTokens
        if (compaction.elides) {
            val before = toolChars(messages, turnStartIndex)
            if (Elider.elide(messages, turnStartIndex, keepRecentRounds) > 0) {
                runCatching { onUsage(TokenUsage(0, 0), "elide") }
                // Summarise only if still over: a stub can free little, and elision alone must not thrash.
                val saved = before - toolChars(messages, turnStartIndex)
                projected -= saved / CHARS_PER_TOKEN_ESTIMATE
                if (projected < triggerTokens) {
                    // Landing just under the trigger means the next round trips it again: top up toward 3/4.
                    Elider.elideLargest(messages, turnStartIndex, (projected - triggerTokens * 3 / 4) * CHARS_PER_TOKEN_ESTIMATE)
                    return true
                }
            }
        }

        val cutIndex = roundBoundaries[roundBoundaries.size - keepRecentRounds]
        val toCompact = messages.subList(turnStartIndex, cutIndex).toList()

        // The user message may already carry an earlier summary after the turn's own text: feed it
        // back in and replace it, so repeated compactions merge instead of stacking.
        val userMessage = messages[turnStartIndex - 1]
        val previous = userMessage.content.removePrefix(turnUserContent + SUMMARY_MARKER)
            .takeIf { userMessage.content.startsWith(turnUserContent + SUMMARY_MARKER) }

        val transcript = (previous?.let { "PREVIOUS SUMMARY:\n$it\n" } ?: "") + toCompact.joinToString("\n") { message ->
            val calls = message.toolCalls?.joinToString(", ") { "${it.name}(${it.argumentsJson})" }
            "${message.role.name}: ${message.content}" + (calls?.let { " [tool calls: $it]" } ?: "")
        }
        val workingSet = WorkingSet.fromEntries(rounds.mapIndexed { i, r ->
            SessionEntry("r$i", null, r.role, r.content, 0L, r.metadata)
        })
        val summary = summariser.summarise(transcript, workingSet, config.model) {
            runCatching { onUsage(it, "summarise") }
        }
        messages.subList(turnStartIndex, cutIndex).clear()
        // The todo results just cut away carried the model's checklist: keep its latest state in the summary.
        val todos = currentTodos(rounds.mapIndexed { i, r -> SessionEntry("r$i", null, r.role, r.content, 0L, r.metadata) })
            ?.takeIf { it.isNotEmpty() }?.let { "\n\nCurrent todo list:\n" + renderTodos(it) }.orEmpty()
        messages[turnStartIndex - 1] = userMessage.copy(content = turnUserContent + SUMMARY_MARKER + summary + todos)
        // Still over after the summary: stub the largest remaining results too (the summarise already counts).
        val afterSummary = projected - (toCompact.sumOf { it.content.length } - summary.length) / CHARS_PER_TOKEN_ESTIMATE
        if (afterSummary >= triggerTokens) elideLargestRelief(messages, turnStartIndex, afterSummary, triggerTokens)
        return true
    }

    /** Elide modes: when whole rounds can't be cut, stub the largest results (newest round too) instead of giving up. */
    private fun elideLargestRelief(messages: MutableList<Message>, from: Int, projected: Int, triggerTokens: Int): Boolean {
        if (!compaction.elides) return false
        // Aim well under the trigger, or the next round pushes it over again and the turn thrashes.
        val needed = (projected - triggerTokens * 3 / 4) * CHARS_PER_TOKEN_ESTIMATE
        val saved = Elider.elideLargest(messages, from, needed)
        if (projected - saved / CHARS_PER_TOKEN_ESTIMATE >= triggerTokens) return false
        runCatching { onUsage(TokenUsage(0, 0), "elide") }
        return true
    }

    private fun toolChars(messages: List<Message>, from: Int): Int =
        (from until messages.size).sumOf { if (messages[it].role == MessageRole.TOOL) messages[it].content.length else 0 }

    /** Moves the user's queued mid-turn messages into this turn, so the next request carries them. */
    private suspend fun applySteers(
        inbox: TurnInbox?, messages: MutableList<Message>, pendingRounds: MutableList<PendingEntry>,
        onEvent: suspend (TurnEvent) -> Unit,
    ) {
        val texts = inbox?.drainSteers().orEmpty()
        if (texts.isEmpty()) return
        // One message for the request, the way replay merges them: strict templates (Gemma) reject adjacent user turns.
        messages.add(Message(MessageRole.USER, texts.joinToString("\n\n")))
        texts.forEach { pendingRounds.add(PendingEntry(EntryRole.USER, it, mapOf("steer" to "true"))) }
        onEvent(TurnEvent.SteerApplied(texts))
    }

    suspend fun turn(
        session: AgentSession,
        userInput: String,
        config: AgentConfig,
        turnPreamble: String? = null,
        inbox: TurnInbox? = null,
        images: List<dev.sophi.ai.api.ImageRef> = emptyList(),
        onEvent: suspend (TurnEvent) -> Unit = {}
    ): AgentSession = streamTurn(session, userInput, config, turnPreamble, inbox, images, onEvent)

    suspend fun streamTurn(
        session: AgentSession,
        userInput: String,
        config: AgentConfig,
        turnPreamble: String? = null,
        inbox: TurnInbox? = null,
        images: List<dev.sophi.ai.api.ImageRef> = emptyList(),
        onEvent: suspend (TurnEvent) -> Unit
    ): AgentSession {
        val turn = TurnProgress(images)
        return try {
            // Fresh per turn (and per nested AgentLoop): tool_search activations die with the turn.
            // A nested loop built without a sink audits to the outer loop's, and obeys its rules.
            val sink = audit ?: currentCoroutineContext()[AuditScope]?.sink
            // A loop built with its own rules inside another loop's turn only adds their deny and ask: it can't loosen the outer ones.
            val outer = currentCoroutineContext()[PermissionScope]?.rules
            val rules = if (permissions == null || outer == null || outer === permissions) permissions ?: outer else outer.tightened(permissions.active)
            val turnHooks = hooks ?: currentCoroutineContext()[HookScope]?.hooks
            val turnGuardian = guardian ?: currentCoroutineContext()[GuardianScope]?.guardian
            var scope: CoroutineContext = ActivatedTools()
            if (sink != null) scope += AuditScope(sink)
            if (rules != null) scope += PermissionScope(rules)
            if (turnHooks != null) scope += HookScope(turnHooks)
            if (turnGuardian != null) scope += GuardianScope(turnGuardian)
            withContext(scope) { runTurn(session, userInput, config, onEvent, turn, turnPreamble, inbox) }
        } catch (e: CancellationException) {
            val limit = currentCoroutineContext()[DenialLimit]?.takeIf { it.tripped }
            if (!turn.persisted) withContext(NonCancellable) {
                if (limit == null) saveStopped(session, userInput, turn)
                else saveStopped(session, userInput, turn, "the denial limit", limit.reason(), TurnStopReason.DeniedUnattended)
            }
            throw e
        } catch (e: BudgetExceededException) {
            // A typed stop, not an error: the rounds so far are kept and the host can say how to raise the budget.
            val message = e.message ?: "[Stopped: the session's budget is spent]"
            if (!turn.persisted) {
                onEvent(TurnEvent.Token(message))
                saveStopped(session, userInput, turn, "the budget", message, TurnStopReason.BudgetExceeded)
            }
            session
        } catch (e: Exception) {
            // A provider/network error after a tool round must not lose it: its files changed, and /undo needs its metadata.
            if (!turn.persisted && turn.pendingRounds.isNotEmpty()) runCatching {
                saveStopped(session, userInput, turn, "an error", "[Stopped by an error: ${e.message}]", TurnStopReason.Error)
            }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    /**
     * The user stopped the turn, or it failed after a tool round. Keeps what it already did — a browser step or a sent message
     * happened — so the next turn knows. A tool call still running gets a result saying so, since
     * a tool call without one is rejected by the model's API on the next request.
     */
    private suspend fun saveStopped(
        session: AgentSession, userInput: String, turn: TurnProgress,
        by: String = "the user", stopMessage: String = STOPPED_BY_USER, reason: TurnStopReason = TurnStopReason.UserStopped,
    ) {
        turn.persisted = true
        session.append(EntryRole.USER, userInput, dev.sophi.core.session.imagesMetadata(turn.images))
        turn.pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
        val last = turn.pendingRounds.lastOrNull()
        if (last?.role == EntryRole.ASSISTANT) {
            val calls = last.metadata["toolCalls"]?.let { runCatching { entryJson.decodeFromString<List<ToolCallRecord>>(it) }.getOrNull() }.orEmpty()
            calls.forEach { c ->
                session.append(EntryRole.TOOL_RESULT, "Stopped by $by before this finished.",
                    mapOf("replay" to "false", "toolCallId" to c.id, "toolName" to c.name))
            }
        }
        session.append(EntryRole.ASSISTANT, stopMessage, mapOf("stopReason" to reason.name))
        sessionManager.save(session)
    }

    /** Before-tool hooks, ahead of every check: a rewritten call is classified, ruled on, planned and confirmed as rewritten. */
    private suspend fun applyBeforeToolHooks(hooks: LoopHooks?, sessionId: String, toolCalls: List<ToolCall>): Pair<List<ToolCall>, Map<String, String>> {
        val vetoes = mutableMapOf<String, String>()
        val calls = if (hooks == null) toolCalls else toolCalls.map { call ->
            when (val r = hooks.beforeTool(sessionId, call.name, call.argumentsJson)) {
                is HookResult.Veto -> call.also { vetoes[it.id] = r.reason }
                is HookResult.Rewrite -> call.copy(argumentsJson = r.argumentsJson)
                else -> call
            }
        }
        return calls to vetoes
    }

    /**
     * The one pipeline every tool call goes through, the model's own and those a tool makes via [ToolCallScope]: rules,
     * plan-first, hook denials, confirmation, checkpoint ([takeCheckpoint]), undo records ([onUndo]), execution, audit,
     * after-tool hook and events. [calls] already went through [applyBeforeToolHooks]; [vetoes] is what it vetoed.
     */
    private suspend fun executeCalls(
        session: AgentSession, userInput: String, calls: List<ToolCall>, vetoes: Map<String, String>, onEvent: suspend (TurnEvent) -> Unit,
        window: Int, takeCheckpoint: Boolean, onUndo: (Map<String, String>) -> Unit,
    ): List<ToolCallOutcome> {
        val hooks = currentCoroutineContext()[HookScope]?.hooks
        val rules = currentCoroutineContext()[PermissionScope]?.rules
        // Rules first: deny > ask > allow; no effect = no rule applies, the tier/grant/policy flow decides.
        val effects = mutableMapOf<String, Effect>()
        val kindsOf = mutableMapOf<String, Set<EffectKind>>()
        val unattended = currentCoroutineContext()[Unattended.Key] != null
        val classified = calls.map { call ->
            val tool = registry.getOrNull(call.name)
            val tier = tool?.riskLevel(call.argumentsJson) ?: RiskLevel.SAFE
            val preview = tool?.confirmationPreview(call.argumentsJson)
            // A tool that throws computing its subjects (e.g. a NUL in a path) gets null: R3, fail closed, not a crashed round.
            val subjects = if (rules == null) null else runCatching { tool?.permissionSubjects(call.argumentsJson) }.getOrNull()
            // A bash command that only writes one workspace file answers to write_file's rules too: the stricter effect wins.
            val writes = if (rules == null) null else runCatching { (tool as? BashTool)?.fileWriteSubject(call.argumentsJson) }.getOrNull()
            val kinds = tool?.callEffects(call.argumentsJson).orEmpty().also { kindsOf[call.id] = it }
            val effect = rules?.let { r ->
                listOfNotNull(r.decide(call.name, subjects, kinds, unattended), writes?.let { r.decide("write_file", listOf(it), setOf(EffectKind.WRITES), unattended) }).maxOrNull()
            }
                ?.also { effects[call.id] = it }
            // an allow written under an ask rule could never apply (ask beats allow), so "always" isn't offered
            val always = if (rules == null || effect == Effect.ASK) null else PermissionRules.alwaysAllowRules(call.name, subjects)
            call to ConfirmationRequest(call.id, call.name, call.argumentsJson, tier, preview, always, askRule = effect == Effect.ASK)
        }
        // Plan-first: changes are held (recorded, not run) whatever a grant or allow/ask rule says; trusted
        // read-only hints run without asking. Numbered here, in call order, before the parallel run.
        val recorder = currentCoroutineContext()[PlanRecorder]
        val reaches = if (recorder == null) emptyMap() else classified.associate { (call, _) ->
            call.id to (registry.getOrNull(call.name)?.reach(call.argumentsJson) ?: Reach.LOCAL)
        }
        val held = mutableMapOf<String, Int>()
        val hintedReads = mutableSetOf<String>()
        if (recorder != null) {
            val unattended = currentCoroutineContext()[Unattended.Key] != null
            classified.forEach { (call, req) ->
                val effect = effects[req.callId]
                if (effect == Effect.DENY || req.riskLevel == RiskLevel.SAFE || req.callId in vetoes) return@forEach
                val reach = reaches.getValue(call.id)
                val hinted = reach == Reach.OUTSIDE_HINTED_READ && recorder.trustReadOnlyHints
                val change = when (recorder.mode) {
                    PlanFirst.OFF -> false
                    PlanFirst.OUTSIDE -> reach == Reach.OUTSIDE_CHANGE
                    PlanFirst.EVERY -> !hinted
                }
                val preAllowed = effect == Effect.ALLOW || call.name in grants
                when {
                    // Unattended, only tools the run was granted may be planned; the rest are denied as today.
                    change && (!unattended || preAllowed) ->
                        held[call.id] = recorder.record(call.name, call.argumentsJson, req.riskLevel, preAllowed)
                    hinted && effect == null && (!unattended || preAllowed) -> hintedReads += call.id
                }
            }
        }
        val needsDecision = classified.filter { (_, req) -> req.callId !in held && req.callId !in hintedReads && req.callId !in vetoes }.filter { (_, req) ->
            when (effects[req.callId]) {
                Effect.ASK -> true
                null -> req.riskLevel != RiskLevel.SAFE && req.toolName !in grants
                else -> false
            }
        }
        val notes = DecisionNotes()
        // ON_CONFIRMATION: a hook may deny, never allow, so it can't approve what a person would be asked.
        val hookDenials = if (hooks == null) emptyMap() else needsDecision.mapNotNull { (_, req) ->
            hooks.denies(session.id, req)?.let { req.callId to it }
        }.toMap()
        hookDenials.keys.forEach { notes.note(it, "hook") }
        val asked = needsDecision.filter { it.second.callId !in hookDenials }
        val decisions = if (asked.isEmpty()) {
            emptyMap()
        } else {
            val requests = asked.map { it.second }
            onEvent(TurnEvent.ConfirmationStarted(requests.map { it.toolName }))
            try {
                withContext(notes) { confirmationPolicy.confirm(requests) }
            } finally {
                onEvent(TurnEvent.ConfirmationFinished)
            }
        }
        val decided = classified.map { (call, req) ->
            val allowed = if (req.callId in held || req.callId in vetoes) false else if (req.callId in hintedReads) true else when (effects[req.callId]) {
                Effect.DENY -> false
                Effect.ALLOW -> true
                Effect.ASK -> decisions[req.callId] == true
                null -> req.riskLevel == RiskLevel.SAFE || req.toolName in grants || decisions[req.callId] == true
            }
            call to allowed
        }
        // Guardian: a second look at DESTRUCTIVE calls about to run that no person approved this round. It can
        // only take an approval back (DENY) or hand it to a person (ASK); everything else stays as decided.
        val verdicts = mutableMapOf<String, GuardianVerdict>()
        currentCoroutineContext()[GuardianScope]?.guardian?.takeIf { it.reviewCalls }?.let { g ->
            // ponytail: reviews run one after another; a round rarely has more than one destructive call
            decided.zip(classified).forEach { (d, c) ->
                val req = c.second
                if (d.second && req.riskLevel == RiskLevel.DESTRUCTIVE && req.callId !in hintedReads && notes.rules[req.callId] !in PERSON_DECISIONS) {
                    verdicts[req.callId] = g.review(req.toolName, registry.getOrNull(req.toolName)?.description.orEmpty(), req.argumentsJson, userInput)
                }
            }
        }
        val guardNotes = DecisionNotes()
        val guardAsks = classified.map { it.second }.filter { verdicts[it.callId]?.effect == Effect.ASK }
        val guardDecisions = if (guardAsks.isEmpty()) emptyMap() else {
            // Routed like an ask rule: auto, threshold and god-mode policies hand it to their person.
            val requests = guardAsks.map { req ->
                req.copy(askRule = true, alwaysAllow = null, preview = "${req.preview ?: req.argumentsJson}\nguardian: ${verdicts.getValue(req.callId).reason}")
            }
            onEvent(TurnEvent.ConfirmationStarted(requests.map { it.toolName }))
            try {
                withContext(guardNotes) { confirmationPolicy.confirm(requests) }
            } finally {
                onEvent(TurnEvent.ConfirmationFinished)
            }
        }
        val allowedCalls = decided.map { (call, allowed) ->
            call to when (verdicts[call.id]?.effect) {
                Effect.DENY -> false
                Effect.ASK -> guardDecisions[call.id] == true
                else -> allowed
            }
        }

        val undoMeta = mutableMapOf<String, String>()
        // A delegate's nested loop (or a code_mode script) runs DESTRUCTIVE tools of its own, whatever the call itself rates.
        if (takeCheckpoint && checkpoints != null && classified.zip(allowedCalls).any { (c, a) ->
                a.second && (c.second.riskLevel == RiskLevel.DESTRUCTIVE || registry.getOrNull(c.first.name).let { it is SubagentTool || it is DecomposeGoalTool || it is dev.sophi.core.tools.CodeModeTool })
            }) {
            val start = System.currentTimeMillis()
            checkpoints.snapshot()?.let { undoMeta += mapOf("checkpoint" to it, "checkpointMs" to "${System.currentTimeMillis() - start}") }
        }
        val changes = RoundChanges(currentCoroutineContext()[RoundChanges])
        // Recorded whether or not this loop knows the root: an enclosing undoableRound (e.g. /goal's) may too.
        allowedCalls.filter { (c, ok) -> ok && c.name == "bash" }.forEach { (c, _) ->
            runCatching { entryJson.parseToJsonElement(c.argumentsJson).jsonObject["command"]?.jsonPrimitive?.content }.getOrNull()
                ?.let(changes::recordCommand)
        }
        // In a finally, so a stopped round still tells /undo what it changed (saveStopped persists it).
        val auditSink = currentCoroutineContext()[AuditScope]?.sink
        val actor = currentCoroutineContext()[ActorContext]
        // Off costs nothing: no judge call, no hashing.
        val readGuard = currentCoroutineContext()[GuardianScope]?.guardian?.takeIf { it.reads != GateMode.OFF }
        // The one cut of a result: a page, at most a fifth of the window; the rest goes to result_read's store, kept per root session.
        val store = (registry.getOrNull(RESULT_READ_NAME) as? ResultReadTool)?.store
        val page = minOf(store?.pageChars ?: DEFAULT_RESULT_PAGE_CHARS, (window * MAX_TOOL_RESULT_FRACTION_OF_WINDOW * CHARS_PER_TOKEN_ESTIMATE).toInt())
        val resultsSession = currentCoroutineContext()[SessionIdContext]?.sessionId ?: "default"
        return try { withContext(changes) {
            allowedCalls.zip(classified) { (call, allowed), (_, req) ->
                async {
                    onEvent(TurnEvent.ToolCallStarted(call.name, call.argumentsJson))
                    val start = System.currentTimeMillis()
                    val step = held[call.id]
                    var readCheck: String? = null
                    var readCheckMs: Long? = null
                    var readHeld = false
                    fun audit(outcome: String, result: String, resultId: String? = null) {
                        val verdict = verdicts[call.id]
                        val rule = if (step != null) "plan" else if (call.id in hintedReads) "read-hint" else if (call.id in vetoes) "hook" else if (readHeld) "read-check"
                            else when (verdict?.effect) { Effect.DENY -> "guardian"; Effect.ASK -> guardNotes.rules[call.id] ?: "policy"; else -> null }
                        val plan = step?.let { "${recorder!!.planId}#$it" }
                        if (auditSink != null) recordAudit(auditSink, actor, session, req, effects[req.callId], notes, outcome, result, start, rule, plan,
                            verdict?.let { "${it.effect.name.lowercase()}: ${it.reason}" }, readCheck, readCheckMs, resultId)
                    }
                    var failed = false
                    val result = if (step != null) {
                        planStepStub(step)
                    } else if (call.id in vetoes) {
                        failed = true
                        "Error: Tool '${call.name}' vetoed by a hook: ${vetoes[call.id]}"
                    } else if (!allowed) {
                        failed = true
                        currentCoroutineContext()[DenialLimit]?.denied(call.name, call.argumentsJson,
                            effects[call.id]?.let { rules?.why(it, call.name, kindsOf[call.id].orEmpty(), unattended) })
                        if (effects[call.id] == Effect.DENY) "Error: Tool '${call.name}' denied by a permission rule"
                        else if (verdicts[call.id]?.effect == Effect.DENY) "Error: Tool '${call.name}' denied by the guardian: ${verdicts.getValue(call.id).reason}"
                        else hookDenials[call.id]?.let { "Error: Tool '${call.name}' denied by a hook: $it" }
                            ?: if (currentCoroutineContext()[Unattended.Key] != null) "Error: Tool '${call.name}' execution denied by " +
                                "confirmation policy: this run is unattended and nobody is here to approve it. Do without it, or finish and say what you needed."
                            else "Error: Tool '${call.name}' execution denied by confirmation policy"
                    } else {
                        registry.getOrNull(call.name)
                            ?.let { tool ->
                                invalidArgumentsError(call.name, call.argumentsJson) // an "Error: " result, counted as failed below
                                    ?: runCatching { tool.execute(call.argumentsJson) }
                                    .getOrElse { e ->
                                        // A stopped turn must stop its tools, not report them as failed.
                                        if (e is CancellationException) { audit("cancelled", ""); throw e }
                                        failed = true; "Error: ${e.message}"
                                    }
                            }
                            ?: run { failed = true; "Error: Tool '${call.name}' not found" }
                    }
                    // Tools signal a handled failure by returning an "Error: "-prefixed string
                    // just as often as by throwing (every built-in tool's own validation follows
                    // this convention) — count both the same way for loop-guard/reliability purposes.
                    failed = failed || isToolError(result)
                    val outcome = if (step != null) "recorded" else if (!allowed) "denied" else if (failed) "error" else "ok"
                    var text = result
                    var held: String? = null
                    val tool = registry.getOrNull(call.name)
                    // Read check: outside text is judged whole, before the limiter keeps it, so every page result_read can
                    // later return was judged. An error from a tool that runs here (a refused address) was written here; an
                    // MCP server's error is the server's text, so it is judged.
                    if (readGuard != null && allowed && step == null && tool?.outsideText == true && result.isNotBlank() &&
                        result != "No results found" && !(isToolError(result) && tool.reach(call.argumentsJson) == Reach.LOCAL)) {
                        if (text.length > READ_CHECK_LIMIT) text = text.take(READ_CHECK_LIMIT) + "\n... cut at the read-check limit"
                        val checkStart = System.currentTimeMillis()
                        val v = try { readGuard.checkRead(text, userInput) } catch (e: CancellationException) { audit(outcome, result); throw e }
                        readCheckMs = if (v.cached) 0 else System.currentTimeMillis() - checkStart
                        val enforce = readGuard.reads == GateMode.ENFORCE
                        readCheck = "${if (enforce) "hold" else "shadow"} ${v.label.name.lowercase()}${if (v.cached) " cached" else ""}: ${v.reason}"
                        if (enforce && v.label != ReadVerdict.Label.CLEAN) {
                            readHeld = true
                            val id = readGuard.hold(call.name, call.argumentsJson, text, v.reason, topSessionId(session), actor?.actorId ?: LOCAL_ACTOR, actor?.tenantId)
                            val why = if (v.label == ReadVerdict.Label.FAILED) "The read check could not judge it (${v.reason})."
                                else "The read check says it may carry instructions aimed at an AI agent: ${v.reason}."
                            held = "[Held: this ${call.name} result was not shown to you. $why Do not retry the same read. " +
                                "Tell the user it was held; they can release it with /release $id.]"
                        }
                    }
                    // A held result is never kept for result_read. result_read's page already fits: cutting it again would hide
                    // its end, and an outside result's page comes back already fenced. Outside text otherwise reaches the model fenced;
                    // an error written here (a refused address) isn't outside text, and stays readable as an error (WorkingSet).
                    val outside = tool?.outsideText == true && !(isToolError(result) && tool.reach(call.argumentsJson) == Reach.LOCAL)
                    val (shown, resultId) = when {
                        held != null -> held to null
                        call.name == RESULT_READ_NAME -> text to null
                        else -> limitResult(text, page, store, resultsSession, outside).let { (s, id) -> (if (outside) fenceAsData(call.name, s) else s) to id }
                    }
                    // Before the suspending event: a turn cancelled while it is delivered still audits a call that ran.
                    audit(outcome, if (allowed) result else "", resultId)
                    val reach = reaches[call.id]
                    if (recorder != null && allowed && !failed && (reach == Reach.OUTSIDE_READ || reach == Reach.OUTSIDE_HINTED_READ)) {
                        recorder.read(call.name, call.argumentsJson, result.take(500), viaHint = reach == Reach.OUTSIDE_HINTED_READ)
                    }
                    val took = System.currentTimeMillis() - start
                    hooks?.afterTool(session.id, call.name, call.argumentsJson, result, failed, took)
                    onEvent(TurnEvent.ToolCallFinished(call.name, result, failed, took, call.argumentsJson))
                    ToolCallOutcome(
                        call,
                        Message(
                            role = MessageRole.TOOL,
                            content = shown,
                            toolCallId = call.id,
                            toolName = call.name
                        ),
                        failed
                    )
                }
            }.awaitAll()
        } } finally {
            undoMeta += undoRecord(changes)
            if (undoMeta.isNotEmpty()) onUndo(undoMeta)
        }
    }

    private suspend fun runTurn(
        session: AgentSession,
        userInput: String,
        config: AgentConfig,
        onEvent: suspend (TurnEvent) -> Unit,
        turn: TurnProgress,
        turnPreamble: String? = null,
        inbox: TurnInbox? = null,
    ): AgentSession {
        val messages = PromptBuilder.build(session.branch()).toMutableList()
        // Volatile context (hints, recall) rides on this turn's request only: prefixed to the user
        // message so the system prompt and all of history stay a byte-stable, cacheable prefix.
        // Never persisted — every session.append below stores the raw userInput.
        // Tool rounds don't replay across turns, so the model's own todo list rides along here the same way.
        val todoNote = currentTodos(session)?.takeIf { it.isNotEmpty() }?.let { "\n\n[Your todo list so far:\n${renderTodos(it)}]" }.orEmpty()
        val turnUserContent = (turnPreamble?.let { "$it\n\n$userInput" } ?: userInput) + todoNote
        messages.add(Message(MessageRole.USER, turnUserContent, images = turn.images))
        // Everything from here on is this turn's own accumulated rounds — the only region
        // mid-loop compaction is ever allowed to touch. The prior-session prefix in front of it
        // is governed separately by cross-turn compaction (AgentConfig.maxBranchLength).
        val turnStartIndex = messages.size

        val window = contextWindowTokens // one value for the whole turn, even if a host switches model mid-turn
        val compactionTriggerTokens = (window * compactionThreshold).toInt()
        var compactionsWithoutRelief = 0
        var toolRound = 0
        val pendingRounds = turn.pendingRounds
        val loopGuardState = LoopGuardState(config.maxToolRounds)
        var nudged = false
        val hooks = currentCoroutineContext()[HookScope]?.hooks

        while (true) {
            val built = CompletionRequest(
                // Asked every round, so a mid-turn switch to a model that can't see sends it no images.
                messages = dev.sophi.ai.api.prepareForModel(messages.toList(), canSee = supportsImages()),
                model = config.model,
                maxTokens = config.maxTokens,
                temperature = config.temperature,
                systemPrompt = config.systemPrompt,
                tools = registry.definitions(currentCoroutineContext()[ActivatedTools]!!.names.toSet())
            )
            val request = hooks?.beforeRequest(session.id, built) ?: built

            val contentBuf = StringBuilder()
            var pendingToolCalls: List<ToolCall>? = null
            var roundUsage: TokenUsage? = null
            var finishReason: String? = null
            var firstEventAt = 0L
            provider.stream(request).collect { event ->
                if (firstEventAt == 0L && event !is StreamEvent.Usage && event !is StreamEvent.Finish) firstEventAt = System.nanoTime()
                when (event) {
                    is StreamEvent.Content -> {
                        contentBuf.append(event.text)
                        onEvent(TurnEvent.Token(event.text))
                    }
                    is StreamEvent.Reasoning -> onEvent(TurnEvent.ReasoningToken(event.text))
                    is StreamEvent.ToolCallsReady -> pendingToolCalls = event.calls
                    is StreamEvent.Finish -> finishReason = event.reason
                    // Cumulative for the whole prompt just sent, so the latest value IS the total.
                    is StreamEvent.Usage -> roundUsage = event.usage
                }
            }

            roundUsage?.let { u ->
                runCatching { onUsage(u, "turn") }
                val generationMillis = if (firstEventAt == 0L) 0L else (System.nanoTime() - firstEventAt) / 1_000_000
                onEvent(TurnEvent.RoundUsage(u, generationMillis))
            }
            val toolCalls = pendingToolCalls
            if (toolCalls == null) {
                // A round with no tool calls AND no text isn't an answer — it's the model stopping
                // mid-thought, usually because a long reasoning block ate the whole output budget.
                // Ending the turn here used to persist an empty assistant entry and flip the UI to
                // Idle with nothing shown, which reads as "it just stopped working".
                if (contentBuf.isBlank()) {
                    val truncated = finishReason.equals(FINISH_REASON_LENGTH, ignoreCase = true)
                    return finishEarly(
                        session, userInput, turn,
                        if (truncated)
                            "the model used its whole ${config.maxTokens}-token output budget " +
                                "(likely on reasoning) without answering or calling a tool — " +
                                "raise maxTokens for this profile"
                        else
                            "the model returned no text and no tool call " +
                                "(finish reason: ${finishReason ?: "not reported"})",
                        if (truncated) TurnStopReason.OutputTruncated else TurnStopReason.EmptyResponse,
                        onEvent
                    )
                }
                if (inbox != null && inbox.snapshot().any { !it.followUp }) {
                    val reply = contentBuf.toString()
                    messages.add(Message(MessageRole.ASSISTANT, reply))
                    pendingRounds.add(PendingEntry(EntryRole.ASSISTANT, reply, emptyMap()))
                    applySteers(inbox, messages, pendingRounds, onEvent)
                    continue
                }
                // "I'll open Gmail now." with no tool call ends the turn having done nothing. One
                // nudge per turn; a second announcement is taken as the answer.
                if (!nudged && request.tools.isNotEmpty() && announcesWithoutActing(contentBuf.toString())) {
                    nudged = true
                    messages.add(Message(MessageRole.ASSISTANT, contentBuf.toString()))
                    messages.add(Message(MessageRole.USER, ACT_NUDGE))
                    continue
                }
                turn.persisted = true
                session.append(EntryRole.USER, userInput, dev.sophi.core.session.imagesMetadata(turn.images))
                pendingRounds.forEach { session.append(it.role, it.content, it.metadata) }
                session.append(EntryRole.ASSISTANT, contentBuf.toString())
                sessionManager.save(session)

                return if (compactor != null && compactor.needsCompaction(session, config, window)) {
                    hooks?.compaction(session.id, after = false)
                    compactor.compact(session, config, onUsage = { runCatching { onUsage(it, "summarise") } }).also {
                        sessionManager.save(it)
                        hooks?.compaction(it.id, after = true)
                    }
                } else {
                    session
                }
            }

            if (toolRound >= config.maxToolRounds) {
                return finishEarly(
                    session, userInput, turn,
                    "reached the tool-round sanity ceiling (${config.maxToolRounds})",
                    TurnStopReason.ToolRoundCeiling, onEvent
                )
            }
            // Hooks act before any check, so a rewritten call is classified, ruled on, planned and confirmed as rewritten.
            val (calls, vetoes) = applyBeforeToolHooks(hooks, session.id, toolCalls)
            messages.add(Message(MessageRole.ASSISTANT, content = "", toolCalls = calls))
            val roundIndex = pendingRounds.size
            pendingRounds.add(PendingEntry(
                EntryRole.ASSISTANT, "",
                mapOf(
                    "replay" to "false",
                    "toolCalls" to entryJson.encodeToString(
                        calls.map { ToolCallRecord(it.id, it.name, it.argumentsJson) })
                )
            ))
            // A tool that drives other tools (code_mode) runs each of them through this same pipeline, one call at a time.
            val callScope = ToolCallScope { name, args ->
                val inner = listOf(ToolCall("inner-" + java.util.UUID.randomUUID().toString().take(8), name, args))
                val (one, veto) = applyBeforeToolHooks(hooks, session.id, inner)
                // No checkpoint of its own: the round took one before the driving call. Its moves and bash commands
                // still reach the round's undo record through the parent RoundChanges.
                executeCalls(session, userInput, one, veto, onEvent, window, takeCheckpoint = false) {}.single().message.content
            }
            val toolOutcomes = withContext(callScope) {
                executeCalls(session, userInput, calls, vetoes, onEvent, window, takeCheckpoint = true) { undo ->
                    pendingRounds[roundIndex] = pendingRounds[roundIndex].let { it.copy(metadata = it.metadata + undo) }
                }
            }
            // Successful calls never trip the failure guard, so a model can repeat one forever without
            // learning anything new. Tell it on the 3rd identical call; the guard steps in at the 6th.
            val toolResults = toolOutcomes.map { o ->
                val n = loopGuardState.countRepeat(o.call.name, o.call.argumentsJson)
                if (n < REPEAT_NOTE_THRESHOLD) o.message
                else o.message.copy(content = o.message.content + "\n\n[Note: you have now made this exact call " +
                    "($n times, same arguments) in this task. Repeating it won't change the result — read what " +
                    "it returned, or try a different call.]")
            }
            messages.addAll(toolResults)
            toolResults.forEach { m ->
                pendingRounds.add(PendingEntry(
                    EntryRole.TOOL_RESULT, m.content,
                    mapOf("replay" to "false", "toolCallId" to (m.toolCallId ?: ""), "toolName" to (m.toolName ?: ""))
                ))
            }
            toolRound++
            applySteers(inbox, messages, pendingRounds, onEvent)

            val guardReason = loopGuardState.afterRound(toolOutcomes, toolRound)
            if (guardReason != null && !loopGuard.askToContinue(guardReason)) {
                return finishEarly(session, userInput, turn, guardReason, TurnStopReason.LoopGuard, onEvent)
            }

            // roundUsage reflects the request that *asked for* these tool calls — sent before any
            // of their results existed — so it alone can't see what was just appended. A round can
            // call several tools at once (e.g. a browsing task firing off a few browser_* calls
            // together), and their combined results can cross the budget even though none of them
            // individually hit the result limiter's page. Add a rough estimate of what just landed
            // so the gate reacts to what's about to be sent next, not to stale pre-append usage.
            val projectedInputTokens = (roundUsage?.inputTokens ?: 0) +
                toolResults.sumOf { it.content.length } / CHARS_PER_TOKEN_ESTIMATE
            if (projectedInputTokens < compactionTriggerTokens) {
                // Relief can only ever be observed on a later round's usage, never on the same
                // value that just triggered compaction — so the reset lives here, not inline.
                compactionsWithoutRelief = 0
            } else {
                val stubbedBefore = stubbedToolCallIds(messages)
                hooks?.compaction(session.id, after = false)
                val compacted = compactInPlace(messages, turnStartIndex, config, turnUserContent, pendingRounds, projectedInputTokens, compactionTriggerTokens)
                hooks?.compaction(session.id, after = true)
                (stubbedToolCallIds(messages) - stubbedBefore).forEach { id ->
                    messages.firstNotNullOfOrNull { m -> m.toolCalls?.firstOrNull { it.id == id } }
                        ?.let { loopGuardState.forgive(it.name, it.argumentsJson) }
                }
                if (!compacted) {
                    return finishEarly(
                        session, userInput, turn,
                        "context budget exhausted — a single round's output exceeds the compaction floor",
                        TurnStopReason.ContextExhausted, onEvent
                    )
                }
                compactionsWithoutRelief++
                if (compactionsWithoutRelief >= MAX_COMPACTIONS_WITHOUT_RELIEF) {
                    return finishEarly(
                        session, userInput, turn,
                        "compaction is thrashing — repeated summarisation isn't reducing context enough",
                        TurnStopReason.CompactionThrashing, onEvent
                    )
                }
            }
        }
    }
}

const val STOPPED_BY_USER = "[Stopped by the user]"

internal const val ACT_NUDGE = "You said what you'd do next but didn't call a tool. If you meant to act, " +
    "call the tool now. If you're done or need something from me, say so."

private val ANNOUNCEMENT = Regex("""\b(I'll|I will|I'm going to|I am going to|Let me(?! know)|Now I'll)\b""", RegexOption.IGNORE_CASE)

/**
 * A short reply whose last sentence promises an action, with no question in it. A heuristic: it
 * misses other phrasings and can catch a real answer that ends "I'll …", which costs one extra
 * round; the nudge tells the model it may simply be done.
 */
internal fun announcesWithoutActing(reply: String): Boolean {
    val text = reply.trim()
    if (text.isEmpty() || text.length > 400 || '?' in text) return false
    val last = text.split(Regex("(?<=[.!])\\s+")).last()
    return ANNOUNCEMENT.containsMatchIn(last)
}
