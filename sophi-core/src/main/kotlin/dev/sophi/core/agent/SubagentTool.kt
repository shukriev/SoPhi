package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.audit.ActorContext
import dev.sophi.core.audit.AuditScope
import dev.sophi.core.audit.noteDecision
import dev.sophi.core.dryrun.PlanRecorder
import dev.sophi.core.permissions.PermissionScope
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.GuardianScope
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.tools.Unattended
import dev.sophi.core.undo.RoundChanges
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

private const val DELEGATE_TOOL_NAME = "delegate_to_subagent"
private const val WORKTREE_PARAM = ""","worktree":{"type":"boolean","description":"Run the subagent in its own git worktree off HEAD, so its file changes land on a branch you can review and merge instead of in the working tree"}"""
private const val BACKGROUND_PARAM = ""","background":{"type":"boolean","description":"Start it behind the conversation and get an id back at once; read its answer later with subagent_result. Nobody can approve its actions: calls that would ask are denied, so use it for read-only work (research, search, review) unless rules allow more"}"""
private const val RESUME_PARAM = ""","resume":{"type":"string","description":"A subagent session id from an earlier delegate answer: continue that subagent, with its history, on this prompt"}"""
private const val UNATTENDED_PROMPT = "You run in the background: nobody can approve your actions, and calls that need approval are denied. " +
    "Take the conservative path, and say in your answer what was blocked."

@Serializable
private data class SubagentArgs(
    @SerialName("subagent_type") val subagentType: String,
    val prompt: String,
    @SerialName("expected_tools") val expectedTools: List<String>? = null,
    val worktree: Boolean = false,
    val description: String? = null,
    val background: Boolean = false,
    val resume: String? = null,
)

class SubagentTool(
    private val definitions: List<AgentDefinition>,
    private val provider: LLMProvider,
    private val fullRegistry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val parentConfig: AgentConfig,
    /**
     * Total context window of the model the nested loop will use, in tokens. Threaded unchanged
     * to every deeper level: a subagent runs on the same provider/model family as its parent
     * unless its definition overrides `model`, and there is deliberately no per-model registry.
     */
    private val contextWindowTokens: Int,
    private val depth: Int = 0,
    private val maxDelegationDepth: Int = 3,
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
    /** The parent's window now, when a host can switch model mid-session; null = [contextWindowTokens]. */
    private val currentWindow: (() -> Int)? = null,
    /** Non-null: the model may ask for a subagent in its own git worktree (`worktree: true`). */
    private val worktrees: SubagentWorktrees? = null,
    /** Non-null: at depth 0 the model may start a subagent in the background (`background: true`). */
    private val background: BackgroundSubagents? = null,
    /** The policy a background subagent, and every subagent it delegates to, runs under: nobody is there to ask. */
    private val backgroundPolicy: ConfirmationPolicy = ConfirmationPolicy.DENY_ALL,
) : Tool {

    private val bg = background.takeIf { depth == 0 }

    /** Nobody can answer an ask rule or a guardian ASK behind the conversation: those are denied whatever [backgroundPolicy] says. */
    private val unattendedPolicy = ConfirmationPolicy { requests ->
        val (asks, rest) = requests.partition { it.askRule }
        asks.associate { noteDecision(it.callId, "deny-all"); it.callId to false } +
            if (rest.isEmpty()) emptyMap() else backgroundPolicy.confirm(rest)
    }

    override val name = DELEGATE_TOOL_NAME
    override val description: String = if (definitions.isEmpty()) {
        "Delegate a task to a subagent. No agent types are currently configured."
    } else {
        "Delegate a task to a subagent. Available agent types:\n" +
            definitions.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }
    override val parametersJson = """
        {"type":"object","properties":{"subagent_type":{"type":"string","description":"Which agent type to delegate to"},"description":{"type":"string","description":"Short 3-5 word description of the task"},"prompt":{"type":"string","description":"The task for the subagent to perform"},"expected_tools":{"type":"array","items":{"type":"string"},"description":"Tool names you expect this subagent to need, if known — declaring them upfront lets it use them without asking again for each call"}${if (worktrees == null) "" else WORKTREE_PARAM}${if (bg == null) "" else BACKGROUND_PARAM}$RESUME_PARAM},"required":["subagent_type","prompt"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override fun riskLevel(argumentsJson: String): RiskLevel {
        val args = runCatching { json.decodeFromString<SubagentArgs>(argumentsJson) }.getOrNull()
            ?: return RiskLevel.SAFE
        return fullRegistry.worstRiskAmong(args.expectedTools)
    }

    override fun effects(argumentsJson: String) = fullRegistry.effectsAmong(
        runCatching { json.decodeFromString<SubagentArgs>(argumentsJson) }.getOrNull()?.expectedTools)

    override suspend fun execute(argumentsJson: String): String {
        val parentSessionId = coroutineContext[SessionIdContext]?.sessionId
            ?: error("no SessionIdContext — the host must wrap turns in it")
        val args = json.decodeFromString<SubagentArgs>(argumentsJson)
        val definition = definitions.find { it.name == args.subagentType }
            ?: return "Error: unknown subagent type '${args.subagentType}'. Available: ${definitions.joinToString(", ") { it.name }}"

        if (depth >= maxDelegationDepth) {
            return "Error: max delegation depth ($maxDelegationDepth) exceeded"
        }

        val inBackground = args.background && bg != null
        // Its changes would be recorded into this turn's plan, which is saved when the turn ends: lost or misattributed.
        if (inBackground && coroutineContext[PlanRecorder] != null)
            return "Error: background subagents don't run while plan-first is on; delegate without background"
        val policy = if (inBackground) unattendedPolicy else confirmationPolicy
        val subSession = if (args.resume == null) sessionManager.create(
            title = "subagent:${definition.name}",
            parentSessionId = parentSessionId
        ) else {
            // before load: a running one may not be saved yet
            background?.runningOn(args.resume)?.takeIf { it.parentSessionId == parentSessionId }?.let {
                return "Error: subagent ${it.id} is still running on that session; wait with subagent_result(\"${it.id}\")"
            }
            val loaded = runCatching { sessionManager.load(args.resume) }.getOrNull()
                ?.takeIf { it.parentSessionId == parentSessionId && it.title?.startsWith("subagent:") == true }
                ?: return "Error: no subagent session ${args.resume} under this conversation"
            val type = loaded.title!!.removePrefix("subagent:")
            if (type != definition.name) return "Error: session ${args.resume} is a $type subagent; pass subagent_type \"$type\""
            // Two resumes in one round run in parallel; only one may run the session, or the last save wins.
            if (background != null && !background.claim(loaded.id))
                return "Error: subagent session ${loaded.id} is already running; wait for it to finish"
            loaded
        }
        val claimed = args.resume?.takeIf { background != null }
        var handedOff = false
        try {

            val worktree = if (args.worktree && worktrees != null) {
                try {
                    worktrees.open(definition.name)
                } catch (e: IllegalStateException) {
                    return "Error: worktree: ${e.message}. Delegate again without worktree to work in the shared tree."
                }
            } else null
            // In a worktree, the file/shell tools are rooted there instead, for this subagent and any it delegates to.
            val registry = if (worktree == null) fullRegistry else try {
                fullRegistry.subset(fullRegistry.names()).also { r ->
                    worktrees!!.toolsAt(worktree.root).filter { r.getOrNull(it.name) != null }.forEach { r.register(it) }
                }
            } catch (e: Exception) {
                worktree.finish("")
                return "Error: worktree: ${e.message}. Delegate again without worktree to work in the shared tree."
            }
            val scopedRegistry = registry.subset(definition.allowedTools)
            if (definition.allowedTools.contains(DELEGATE_TOOL_NAME)) {
                scopedRegistry.register(
                    SubagentTool(
                        definitions = definitions,
                        provider = provider,
                        fullRegistry = registry,
                        sessionManager = sessionManager,
                        parentConfig = parentConfig,
                        contextWindowTokens = contextWindowTokens,
                        depth = depth + 1,
                        maxDelegationDepth = maxDelegationDepth,
                        confirmationPolicy = policy,
                        currentWindow = currentWindow,
                        // a subagent in a worktree shares it with its own subagents
                        worktrees = worktrees.takeIf { worktree == null },
                    )
                )
            }

            val nestedLoop = AgentLoop(
                provider, scopedRegistry, sessionManager,
                confirmationPolicy = policy,
                grants = scopedRegistry.safeGrantsFrom(args.expectedTools),
                contextWindowTokens = currentWindow?.invoke() ?: contextWindowTokens
            )
            val config = AgentConfig(
                model = definition.model ?: parentConfig.model,
                systemPrompt = listOfNotNull(
                    definition.systemPrompt,
                    worktree?.let {
                        "You work in ${it.root}, a separate git worktree of this project on branch ${it.branch}. " +
                            "Keep every file and shell path inside it; your changes are committed to that branch when you finish."
                    },
                    UNATTENDED_PROMPT.takeIf { inBackground },
                    dev.sophi.core.tools.DATA_FENCE_NOTE,
                ).joinToString("\n\n")
            )
            val footer = "\n\n(subagent session ${subSession.id}; pass resume=\"${subSession.id}\" to continue it)"
            if (!inBackground) return runNested(nestedLoop, subSession, args.prompt, config, worktree, definition.name) + footer

            val info = try {
                bg!!.start(
                    parentSessionId, definition.name, args.description ?: args.prompt.take(40), subSession.id,
                    inheritedByBackground(coroutineContext) + Unattended + RoundChanges(),
                ) {
                    try { runNested(nestedLoop, subSession, args.prompt, config, worktree, definition.name) + footer }
                    finally { claimed?.let { background!!.release(it) } }
                }
            } catch (e: IllegalStateException) {
                worktree?.finish("")
                return "Error: ${e.message}"
            }
            handedOff = true
            return "Started subagent ${info.id} (${definition.name}). Keep working; read its result with subagent_result(\"${info.id}\") — wait_seconds waits for it."
        } finally {
            if (!handedOff) claimed?.let { background!!.release(it) }
        }
    }

    /**
     * What a background run keeps of this turn's context: who it runs for, and the audit, permission, hook and guardian
     * scopes. The rest (job, dispatcher, fallback place, plan recorder, this turn's tool-call scope) belongs to a turn
     * that ends before the run does. It gets its own RoundChanges: this turn's undo round closes without it.
     */
    private fun inheritedByBackground(ctx: CoroutineContext): CoroutineContext =
        listOfNotNull(ctx[SessionIdContext], ctx[ActorContext], ctx[AuditScope], ctx[PermissionScope], ctx[HookScope], ctx[GuardianScope])
            .fold(EmptyCoroutineContext as CoroutineContext) { acc, e -> acc + e }

    private suspend fun runNested(
        nestedLoop: AgentLoop, subSession: AgentSession, prompt: String, config: AgentConfig,
        worktree: SubagentWorktrees.Worktree?, type: String,
    ): String {
        if (worktree == null) return try {
            val result = nestedLoop.turn(subSession, prompt, config)
            result.tip?.content ?: "Error: subagent produced no output"
        } finally {
            sessionManager.save(subSession)
        }
        var answer: String? = null
        try {
            // Its own RoundChanges, no parent: /undo restores the main tree, and the worktree's changes live on its branch.
            answer = withContext(RoundChanges()) { nestedLoop.turn(subSession, prompt, config) }.tip?.content
                ?: "Error: subagent produced no output"
        } finally {
            // finish is blocking git that never throws; it runs whether the turn returned, threw or was cancelled
            val line = worktree.finish("subagent $type: $prompt")
            if (answer != null) answer = "$answer\n\n$line"
            sessionManager.save(subSession)
        }
        return answer!!
    }
}
