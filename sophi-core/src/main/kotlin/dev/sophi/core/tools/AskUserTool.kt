package dev.sophi.core.tools

import dev.sophi.core.session.SessionIdContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

data class Choice(val label: String, val description: String? = null)

data class UserQuestion(
    val sessionId: String,
    val text: String,
    val choices: List<Choice>,
    /** Reply box under the options. False only when the options are the whole answer. */
    val allowFreeText: Boolean,
    /** Asked from a run nobody is watching (a scheduled task); it times out. */
    val unattended: Boolean,
    val deadlineMs: Long?,
)

sealed interface Answer {
    data class Reply(val choice: Choice?, val text: String?) : Answer
    data object Dismissed : Answer
    data object TimedOut : Answer
}

/** How a host asks: the CLI in the terminal, the companion in its bubble. [timeoutMs] null = wait for the answer. */
fun interface UserAsker {
    suspend fun ask(question: UserQuestion, timeoutMs: Long?): Answer
}

/** Marks a run nobody is watching (ScheduleEngine sets it); nested coroutines and subagents inherit it. */
object Unattended : AbstractCoroutineContextElement(Unattended.Key) {
    object Key : CoroutineContext.Key<Unattended>
}

/**
 * Lets Sophi ask the user something mid-turn, through whatever [asker] the host passes. Unattended runs
 * (scheduled tasks) time out after [unattendedTimeoutMs] and the model is told to carry on.
 */
class AskUserTool(
    private val asker: UserAsker,
    private val unattendedTimeoutMs: Long = UNATTENDED_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Tool {
    override val name = "ask_user"

    override val description = """
        Ask the user a question and wait for the answer. Use ONLY when you are genuinely blocked on
        a decision that belongs to the user — a preference, a choice between approaches, missing
        information you cannot find yourself. Do not use it to confirm things you can decide, or
        to report progress.
        Give 2-4 options when the answer is a choice; put your recommended option first. The user
        can also reply in free text unless allow_free_text is false.
    """.trimIndent()

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "question": { "type": "string", "description": "The question, one or two sentences." },
            "options": {
              "type": "array",
              "maxItems": $MAX_OPTIONS,
              "description": "Optional choices, recommended first.",
              "items": {
                "type": "object",
                "properties": {
                  "label": { "type": "string", "description": "Short option name." },
                  "description": { "type": "string", "description": "One line on what it means or costs." }
                },
                "required": ["label"]
              }
            },
            "allow_free_text": { "type": "boolean", "description": "Show a reply box. Defaults to true." }
          },
          "required": ["question"]
        }
    """.trimIndent()

    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String {
        val sessionId = coroutineContext[SessionIdContext]?.sessionId
            ?: return "Error: ask_user only works inside a session — there is no one to ask."
        val args = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
            ?: return "Error: arguments must be a JSON object with a \"question\" field."
        val question = (args["question"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (question.isEmpty()) return "Error: \"question\" is required."
        val choices = parseChoices(args["options"])
        val allowFreeText = (args["allow_free_text"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (choices.size > MAX_OPTIONS) return "Error: at most $MAX_OPTIONS options — narrow them down and ask again."
        if (choices.isEmpty() && !allowFreeText) return "Error: nothing to answer with — give options or allow free text."

        val unattended = coroutineContext[Unattended.Key] != null
        val timeoutMs = if (unattended) unattendedTimeoutMs else null
        val q = UserQuestion(sessionId, question, choices, allowFreeText, unattended, timeoutMs?.let { nowMs() + it })
        return describeAnswer(q, asker.ask(q, timeoutMs), timeoutMs)
    }

    companion object {
        const val MAX_OPTIONS = 4
        const val UNATTENDED_TIMEOUT_MS = 4 * 60_000L
    }
}

/** Objects per the schema, but plain strings too — models send `["Red","Blue"]` often enough that
 *  rejecting it would just cost a retry. Blank labels are dropped. */
private fun parseChoices(element: JsonElement?): List<Choice> =
    (element as? JsonArray).orEmpty().mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { Choice(it) }
            is JsonObject -> (item["label"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { label ->
                    Choice(label, (item["description"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
                }
            else -> null
        }
    }

private const val DISMISSED = "User dismissed the question — proceed with your best judgment or stop."

fun describeAnswer(question: UserQuestion, answer: Answer, timeoutMs: Long?): String = when (answer) {
    is Answer.Reply -> {
        val note = answer.text?.trim()?.takeIf { it.isNotEmpty() }
        val index = answer.choice?.let { question.choices.indexOf(it) } ?: -1
        val picked = if (index >= 0) "User chose ${'A' + index}: \"${question.choices[index].label}\"" else null
        when {
            picked != null && note != null -> "$picked. Note: $note"
            picked != null -> picked
            note != null -> "User replied: $note"
            else -> DISMISSED
        }
    }
    Answer.Dismissed -> DISMISSED
    Answer.TimedOut ->
        "User did not answer within ${(timeoutMs ?: 0) / 60_000} minutes — they may be away. " +
            "Proceed with your best judgment or stop."
}
