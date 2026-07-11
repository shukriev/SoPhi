package dev.sophi.learning

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
internal data class VerdictLesson(
    val text: String, val kind: String = "approach",
    val global: Boolean = false, val supersedes: String? = null
)

@Serializable
internal data class VerdictFeedback(
    val entryIndex: Int, val polarity: String, val signal: String,
    val evidence: String = "", val retryOf: Int? = null
)

@Serializable
internal data class EvaluatorVerdict(
    val judgment: String, val reason: String = "",
    val lessons: List<VerdictLesson> = emptyList(),
    val feedback: List<VerdictFeedback> = emptyList()
)

class SessionEvaluator(
    private val provider: LLMProvider,
    private val lessons: LessonStore,
    private val outcomesLog: JsonlLog,
    private val config: LearningConfig,
    private val preferences: PreferenceStore? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun evaluate(sessionId: String, entries: List<SessionEntry>, mechanical: SessionOutcome) {
        if (!config.distillation) return
        runCatching {
            val verdict = requestVerdict(buildPrompt(entries, mechanical)) ?: return
            outcomesLog.append(json.encodeToString(SessionOutcome.serializer(),
                mechanical.copy(ts = System.currentTimeMillis(),
                    judgment = verdict.judgment, reason = verdict.reason)))
            verdict.lessons.forEach { vl ->
                vl.supersedes?.let { lessons.archive(it) }
                lessons.add(Lesson(
                    id = "les_" + UUID.randomUUID(), ts = System.currentTimeMillis(),
                    scope = if (vl.global) "*" else config.scope,
                    sessionId = sessionId, text = vl.text, kind = vl.kind
                ))
            }
            if (config.implicitFeedback && preferences != null) {
                verdict.feedback.filter { it.evidence.isNotBlank() }.forEach { fb ->
                    preferences.add(PreferenceRecord(
                        id = "pref_" + UUID.randomUUID(), ts = System.currentTimeMillis(),
                        scope = config.scope, sessionId = sessionId, entryIndex = fb.entryIndex,
                        polarity = fb.polarity, source = "implicit", signal = fb.signal,
                        evidence = fb.evidence, weight = config.implicitWeight))
                    fb.retryOf?.let { rejected ->
                        if (fb.polarity == "positive") preferences.link(sessionId, rejected, fb.entryIndex)
                    }
                }
            }
        }
    }

    internal fun buildPrompt(entries: List<SessionEntry>, mechanical: SessionOutcome): String {
        val active = lessons.activeIncludingGlobal(config.scope)
        val archivedTexts = lessons.archived(config.scope).takeLast(10)
        return buildString {
            appendLine("You are evaluating a finished agent session. Respond with ONLY a JSON object:")
            appendLine("""{"judgment":"success|partial|failure","reason":"one sentence",""")
            appendLine(""" "lessons":[{"text":"...","kind":"tool_usage|environment|approach|user_context","global":false,"supersedes":null}]}""")
            appendLine("Rules: emit only lessons NOT already covered below; if an existing lesson is wrong,")
            appendLine("emit a correction with \"supersedes\":\"<its id>\". Mark project-independent lessons \"global\":true.")
            appendLine("Do NOT re-emit archived lessons. Emit [] when there is nothing genuinely reusable.")
            appendLine("\n## Existing active lessons\n" +
                active.joinToString("\n") { "- [${it.id}] ${it.text}" }.ifEmpty { "(none)" })
            appendLine("\n## Archived (do not re-emit)\n" +
                archivedTexts.joinToString("\n") { "- ${it.text}" }.ifEmpty { "(none)" })
            if (preferences != null) {
                appendLine("\n## Recent user feedback (for preference lessons)")
                val recent = preferences.active(config.scope).takeLast(20)
                appendLine(recent.joinToString("\n") {
                    "- [${it.source} ${it.polarity} w=${it.weight}] ${it.reason ?: it.evidence ?: ""}"
                }.ifEmpty { "(none)" })
                appendLine("""
Preference rules: you may emit lessons with "kind":"preference" describing durable user preferences.
An explicit record with a reason justifies a preference lesson on its own.
Implicit records justify one only when >= ${config.corroborationThreshold} records show the same pattern.
Also, if this session's transcript shows the user correcting or rephrasing after an assistant reply,
report it in "feedback" with the entryIndex, a signal, and a VERBATIM quote as evidence.
Use "retryOf" when a later reply is a retry of an earlier rejected one.""")
            }
            appendLine("\n## Mechanical facts")
            appendLine("turns=${mechanical.turns} toolCalls=${mechanical.toolCalls} " +
                "toolErrors=${mechanical.toolErrors} ended=${mechanical.outcome}")
            appendLine("\n## Trajectory")
            appendLine(TrajectoryRenderer.render(entries, config.evaluatorInputBudget))
        }
    }

    private suspend fun requestVerdict(prompt: String): EvaluatorVerdict? {
        var text = completeText(prompt) ?: return null
        parse(text)?.let { return it }
        text = completeText("Respond with ONLY the JSON object, no prose.\n\n$prompt") ?: return null
        return parse(text)
    }

    private suspend fun completeText(prompt: String): String? =
        when (val r = runCatching {
            provider.complete(CompletionRequest(
                messages = listOf(Message(MessageRole.USER, prompt)),
                model = config.evaluatorModel ?: config.sessionModel ?: return null,
                maxTokens = 1024, temperature = 0.0))
        }.getOrNull()) {
            is LLMResponse.Text -> r.content
            else -> null
        }

    private fun parse(text: String): EvaluatorVerdict? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString(EvaluatorVerdict.serializer(), text.substring(start, end + 1))
        }.getOrNull()
    }
}
