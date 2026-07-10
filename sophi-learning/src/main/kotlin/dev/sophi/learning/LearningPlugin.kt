package dev.sophi.learning

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LearningPlugin(
    private val config: LearningConfig,
    private val model: String? = null,
    private val provider: dev.sophi.ai.api.LLMProvider? = null,
    private val sessionManager: dev.sophi.core.session.SessionManager? = null
) : SophiPlugin {
    override val name = "learning"

    private val json = Json { encodeDefaults = true }
    private val toolEvents = JsonlLog(config.home.resolve("tool-events.jsonl"))
    private val outcomes = JsonlLog(config.home.resolve("session-outcomes.jsonl"))
    val toolStats = ToolStatsStore(toolEvents, config.recentWindow)
    val lessonStore = LessonStore(JsonlLog(config.home.resolve("lessons.jsonl")), config.maxActiveLessons)
    val preferenceStore = PreferenceStore(JsonlLog(config.home.resolve("preferences.jsonl")))
    private val evaluator = provider?.let { SessionEvaluator(it, lessonStore, outcomes, config) }
    private val lessonsSection = LessonsSection(RecencyUsageRecall(lessonStore, config.maxRecalledLessons), lessonStore, config)
    private val reliabilitySection = ToolReliabilitySection(toolStats, config)

    private class Acc {
        // Mutated from concurrent async tool dispatches; atomics prevent lost updates.
        val turns = AtomicInteger(0)
        val toolCalls = AtomicInteger(0)
        val toolErrors = AtomicInteger(0)
        @Volatile var errored: Boolean = false
    }
    private val accs = ConcurrentHashMap<String, Acc>()

    override fun hooks(): List<AgentHook> = listOf(
        hook(HookPoint.AFTER_TOOL) { ctx: HookContext ->
            val acc = accs.getOrPut(ctx.sessionId) { Acc() }
            acc.toolCalls.incrementAndGet()
            if (ctx.success == false) acc.toolErrors.incrementAndGet()
            append(toolEvents, json.encodeToString(ToolEvent.serializer(), ToolEvent(
                ts = System.currentTimeMillis(), scope = config.scope, sessionId = ctx.sessionId,
                tool = ctx.toolName ?: "unknown", success = ctx.success != false,
                durationMillis = ctx.durationMillis ?: 0,
                errorSnippet = if (ctx.success == false) ctx.toolResult?.take(200) else null
            )))
        },
        hook(HookPoint.AFTER_TURN) { ctx: HookContext ->
            val acc = accs.getOrPut(ctx.sessionId) { Acc() }
            acc.turns.incrementAndGet()
            writeOutcome(ctx.sessionId, "open", acc)
        },
        hook(HookPoint.ON_ERROR) { ctx: HookContext -> accs.getOrPut(ctx.sessionId) { Acc() }.errored = true }
    )

    suspend fun recordSessionEnd(sessionId: String) {
        val acc = accs.remove(sessionId) ?: Acc()
        val mechanical = SessionOutcome(
            ts = System.currentTimeMillis(), scope = config.scope, sessionId = sessionId,
            outcome = if (acc.errored) "error" else "completed",
            turns = acc.turns.get(), toolCalls = acc.toolCalls.get(), toolErrors = acc.toolErrors.get(),
            model = model
        )
        runCatching { outcomes.append(json.encodeToString(SessionOutcome.serializer(), mechanical)) }
        val sm = sessionManager ?: return
        val eval = evaluator ?: return
        runCatching { eval.evaluate(sessionId, sm.load(sessionId).entries, mechanical) }
    }

    fun recordExplicitFeedback(sessionId: String, entryIndex: Int, polarity: String, reason: String?) {
        preferenceStore.add(PreferenceRecord(
            id = "pref_" + java.util.UUID.randomUUID(), ts = System.currentTimeMillis(),
            scope = config.scope, sessionId = sessionId, entryIndex = entryIndex,
            polarity = polarity, source = "explicit", reason = reason, weight = 1.0))
        if (polarity == "positive") {
            preferenceStore.forSession(sessionId)
                .filter { it.polarity == "negative" && it.pairedWith == null &&
                          it.entryIndex < entryIndex &&
                          entryIndex - it.entryIndex <= config.retryWindow * 4 }
                .maxByOrNull { it.entryIndex }
                ?.let { preferenceStore.link(sessionId, it.entryIndex, entryIndex) }
        }
    }

    fun promptSections(scope: String): String? {
        val parts = listOfNotNull(
            runCatching { reliabilitySection.render(scope) }.getOrNull(),
            runCatching { lessonsSection.render(scope) }.getOrNull())
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    private fun writeOutcome(sessionId: String, outcome: String, acc: Acc) {
        append(outcomes, json.encodeToString(SessionOutcome.serializer(), SessionOutcome(
            ts = System.currentTimeMillis(), scope = config.scope, sessionId = sessionId,
            outcome = outcome, turns = acc.turns.get(), toolCalls = acc.toolCalls.get(),
            toolErrors = acc.toolErrors.get(), model = model
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
