package dev.sophi.core.agent.plan

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface Planner {
    suspend fun plan(goalPrompt: String, context: List<String> = emptyList()): Plan
    suspend fun replan(current: Plan, anchorStepId: String, reason: String, context: List<String> = emptyList()): Plan
}

@Serializable
private data class PlanStepJson(
    val id: String,
    val instruction: String,
    val dependsOn: List<String> = emptyList(),
    val decompose: Boolean = false
)

@Serializable
private data class PlanJson(val steps: List<PlanStepJson>)

/**
 * `replan` is a second method on the same interface, not a separate Replanner type — it's the
 * same underlying capability (goal + context -> steps) applied to a partial plan instead of a
 * fresh one (ADR-018).
 */
class LlmPlanner(
    private val provider: LLMProvider,
    private val model: String,
    private val maxTokens: Int = 1024,
    private val contextProvider: suspend (goalPrompt: String) -> List<String> = { emptyList() }
) : Planner {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun plan(goalPrompt: String, context: List<String>): Plan {
        val effectiveContext = context.ifEmpty { contextProvider(goalPrompt) }
        val prompt = buildPlanPrompt(goalPrompt, effectiveContext)
        val steps = requestSteps(prompt) ?: listOf(PlanStepJson(id = "step_1", instruction = goalPrompt))
        return Plan(id = Plan.newId(), goalPrompt = goalPrompt, steps = steps.map { it.toPlanStep() })
    }

    override suspend fun replan(current: Plan, anchorStepId: String, reason: String, context: List<String>): Plan {
        val doneSteps = current.steps.filter { it.status == StepStatus.Done }
        val anchor = current.steps.find { it.id == anchorStepId }
        val prompt = buildReplanPrompt(current, anchorStepId, reason, context)
        val newTail = requestSteps(prompt)?.map { it.toPlanStep() }
            ?: listOf(PlanStep(id = anchorStepId, instruction = "Retry: ${anchor?.instruction ?: reason}"))
        return Plan(
            id = current.id, goalPrompt = current.goalPrompt,
            steps = doneSteps + newTail, version = current.version + 1, parentPlanId = current.id
        )
    }

    private fun PlanStepJson.toPlanStep() =
        PlanStep(id = id, instruction = instruction, dependsOn = dependsOn, decompose = decompose)

    private fun buildPlanPrompt(goalPrompt: String, context: List<String>): String = buildString {
        appendLine("Break the following goal into an ordered list of concrete steps. Respond with ONLY a JSON object:")
        appendLine("""{"steps":[{"id":"step_1","instruction":"...","dependsOn":[],"decompose":false}]}""")
        appendLine("Rules: each step needs a unique \"id\"; \"dependsOn\" lists ids of steps that must finish")
        appendLine("first (omit or leave empty for steps that can run independently/first). Keep the plan as")
        appendLine("short as the goal genuinely requires — a simple goal should produce one or two steps.")
        appendLine("Set \"decompose\" to true only for a step that is a multi-step project in its own right —")
        appendLine("it will be expanded into its own sub-plan rather than attempted in one go. A step a capable")
        appendLine("agent could finish in a single sitting must leave it false. Most steps are false.")
        appendLine("If the goal enumerates or implies many items whose exact count isn't known yet (\"each X\",")
        appendLine("\"every X\", \"one per X\"), do not try to name every item as its own step up front —")
        appendLine("produce a first step that discovers/lists the items, and set \"decompose\" true on it so")
        appendLine("each item gets its own sub-plan (and its own context budget) once the count is known.")
        if (context.isNotEmpty()) {
            appendLine("\n## Relevant context")
            appendLine(context.joinToString("\n\n"))
        }
        appendLine("\n## Goal")
        appendLine(goalPrompt)
    }

    private fun buildReplanPrompt(current: Plan, anchorStepId: String, reason: String, context: List<String>): String =
        buildString {
            appendLine("An in-progress plan needs to change. Respond with ONLY a JSON object with the SAME")
            appendLine("shape as before: {\"steps\":[{\"id\":\"...\",\"instruction\":\"...\",\"dependsOn\":[],\"decompose\":false}]}")
            appendLine("Produce ONLY the steps needed from this point forward (do not repeat already-completed")
            appendLine("steps below) — reuse the anchor step's id if you're replacing it, or invent new ids for")
            appendLine("additional steps.")
            appendLine("\n## Goal")
            appendLine(current.goalPrompt)
            appendLine("\n## Already completed")
            appendLine(current.steps.filter { it.status == StepStatus.Done }
                .joinToString("\n") { "- [${it.id}] ${it.instruction}" }.ifEmpty { "(none)" })
            appendLine("\n## What needs to change")
            val anchorInstruction = current.steps.find { it.id == anchorStepId }?.instruction ?: "(extend the plan)"
            appendLine("Anchor step: [$anchorStepId] $anchorInstruction")
            appendLine("Reason: $reason")
            if (context.isNotEmpty()) {
                appendLine("\n## Relevant context")
                appendLine(context.joinToString("\n\n"))
            }
        }

    private suspend fun requestSteps(prompt: String): List<PlanStepJson>? {
        var text = completeText(prompt) ?: return null
        parseSteps(text)?.let { return it }
        text = completeText("Respond with ONLY the JSON object, no prose.\n\n$prompt") ?: return null
        return parseSteps(text)
    }

    private suspend fun completeText(prompt: String): String? =
        when (val r = runCatching {
            provider.complete(CompletionRequest(
                messages = listOf(Message(MessageRole.USER, prompt)),
                model = model, maxTokens = maxTokens, temperature = 0.0))
        }.getOrNull()) {
            is LLMResponse.Text -> r.content
            else -> null
        }

    private fun parseSteps(text: String): List<PlanStepJson>? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(PlanJson.serializer(), text.substring(start, end + 1)).steps }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }
}
