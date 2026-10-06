package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.learning.JsonlLog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path

sealed class LearnResult {
    object NotReusable : LearnResult()
    data class Learned(val id: String, val title: String, val updated: Boolean) : LearnResult()
    data class Dropped(val stage: String, val reasons: List<String>) : LearnResult()
}

/**
 * Turns a finished turn into an auto-skill, fully automatically:
 * reflect → code gate → security review → write (docs/superpowers/specs/2026-10-06-auto-learned-skills-design.md).
 * Every outcome except "not reusable" is one line in [log]. It never throws.
 */
class AutoSkillLearner(
    private val reflector: WorkflowReflector,
    private val review: SkillSecurityReview,
    private val store: AutoSkillStore = AutoSkillStore(),
    private val log: JsonlLog? = null,
) {
    /** [maxTokens] should be the chat profile's: reasoning models think inside that budget first. */
    constructor(
        provider: LLMProvider, model: String, skillsDir: Path, log: JsonlLog?,
        maxTokens: Int = 4096, timeoutMs: Long = 600_000,
    ) : this(
        WorkflowReflector(provider, model, timeoutMs, maxTokens),
        SkillSecurityReview(provider, model, timeoutMs, maxTokens),
        AutoSkillStore(skillsDir), log,
    )

    /** [canSave] is asked right before writing: the user may switch learning off while this runs. */
    suspend fun learn(turn: FinishedTurn, fromWeb: Boolean, canSave: () -> Boolean = { true }): LearnResult {
        val result = try { run(turn, fromWeb, canSave) } catch (e: Exception) {
            LearnResult.Dropped("error", listOf(e.message ?: e::class.simpleName.orEmpty()))
        }
        if (result != LearnResult.NotReusable) record(result)
        return result
    }

    private suspend fun run(turn: FinishedTurn, fromWeb: Boolean, canSave: () -> Boolean): LearnResult {
        val existing = store.list().map { (id, s) -> id to "${s.metadata.title}: ${s.metadata.description}" }
        val r = reflector.reflect(turn, existing) ?: return LearnResult.Dropped("reflect", listOf("no usable reflection"))
        if (!r.reusable) return LearnResult.NotReusable
        val id = r.update?.takeIf { u -> existing.any { it.first == u } }
            ?: autoSkillId(r.id.ifBlank { r.title })
            ?: return LearnResult.Dropped("reflect", listOf("no usable id"))
        val draft = SkillDraft(id, r.title, r.description, r.params, r.body, fromWeb)
        store.check(draft).takeIf { it.isNotEmpty() }?.let { return LearnResult.Dropped("gate", it) }
        val verdict = review.review(store.render(draft), turn.request)
        if (!verdict.safe) return LearnResult.Dropped("review", verdict.reasons)
        if (!canSave()) return LearnResult.Dropped("off", listOf("auto-learning was switched off"))
        return when (val w = store.write(draft)) {
            is AutoSkillWrite.Written -> LearnResult.Learned(w.id, draft.title, w.updated)
            is AutoSkillWrite.Rejected -> LearnResult.Dropped("gate", w.reasons)
        }
    }

    private fun record(result: LearnResult) {
        val line = buildJsonObject {
            put("ts", System.currentTimeMillis())
            when (result) {
                is LearnResult.Learned -> { put("outcome", "learned"); put("id", result.id); put("updated", result.updated) }
                is LearnResult.Dropped -> {
                    put("outcome", "dropped"); put("stage", result.stage)
                    putJsonArray("reasons") { result.reasons.forEach { add(JsonPrimitive(it)) } }
                }
                LearnResult.NotReusable -> Unit
            }
        }
        runCatching { log?.append(line.toString()) }
    }
}
