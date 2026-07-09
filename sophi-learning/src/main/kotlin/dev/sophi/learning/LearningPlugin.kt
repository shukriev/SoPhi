package dev.sophi.learning

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class LearningPlugin(
    private val config: LearningConfig,
    private val model: String? = null
) : SophiPlugin {
    override val name = "learning"

    private val json = Json { encodeDefaults = true }
    private val toolEvents = JsonlLog(config.home.resolve("tool-events.jsonl"))
    private val outcomes = JsonlLog(config.home.resolve("session-outcomes.jsonl"))
    val toolStats = ToolStatsStore(toolEvents, config.recentWindow)

    private data class Acc(var turns: Int = 0, var toolCalls: Int = 0, var toolErrors: Int = 0, var errored: Boolean = false)
    private val accs = ConcurrentHashMap<String, Acc>()

    override fun hooks(): List<AgentHook> = listOf(
        hook(HookPoint.AFTER_TOOL) { ctx: HookContext ->
            val acc = accs.getOrPut(ctx.sessionId) { Acc() }
            acc.toolCalls++
            if (ctx.success == false) acc.toolErrors++
            append(toolEvents, json.encodeToString(ToolEvent.serializer(), ToolEvent(
                ts = System.currentTimeMillis(), scope = config.scope, sessionId = ctx.sessionId,
                tool = ctx.toolName ?: "unknown", success = ctx.success != false,
                durationMillis = ctx.durationMillis ?: 0,
                errorSnippet = if (ctx.success == false) ctx.toolResult?.take(200) else null
            )))
        },
        hook(HookPoint.AFTER_TURN) { ctx: HookContext ->
            val acc = accs.getOrPut(ctx.sessionId) { Acc() }
            acc.turns++
            writeOutcome(ctx.sessionId, "open", acc)
        },
        hook(HookPoint.ON_ERROR) { ctx: HookContext -> accs.getOrPut(ctx.sessionId) { Acc() }.errored = true }
    )

    fun recordSessionEnd(sessionId: String) {
        val acc = accs.remove(sessionId) ?: Acc()
        writeOutcome(sessionId, if (acc.errored) "error" else "completed", acc)
    }

    private fun writeOutcome(sessionId: String, outcome: String, acc: Acc) {
        append(outcomes, json.encodeToString(SessionOutcome.serializer(), SessionOutcome(
            ts = System.currentTimeMillis(), scope = config.scope, sessionId = sessionId,
            outcome = outcome, turns = acc.turns, toolCalls = acc.toolCalls,
            toolErrors = acc.toolErrors, model = model
        )))
    }

    private fun append(log: JsonlLog, line: String) {
        runCatching { log.append(line) }   // learning must never break a turn
    }

    private fun hook(p: HookPoint, body: suspend (HookContext) -> Unit): AgentHook =
        object : AgentHook {
            override val point = p
            override suspend fun invoke(context: HookContext) { runCatching { body(context) } }
        }
}
