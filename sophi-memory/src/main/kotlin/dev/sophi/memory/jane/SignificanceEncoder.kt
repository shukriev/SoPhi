package dev.sophi.memory.jane

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.memory.TurnObservation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class VerdictMemory(
    val text: String,
    val room: String,
    val emph: Double = 0.0,
    val aff: Double = 0.0,
    val sensitivity: String = "PERSONAL",
    val provenance: String = "USER_DIRECT",
    val causedBy: List<String> = emptyList(),
    val thread: String? = null,
    val supersedes: String? = null
)

@Serializable
internal data class VerdictProfile(val path: String, val value: String)

@Serializable
internal data class EncoderVerdict(
    val memories: List<VerdictMemory> = emptyList(),
    val profile: List<VerdictProfile> = emptyList()
)

/**
 * The per-turn encoding judge (spec §7). One structured-output call; judges what only an
 * LLM can (significance, room, emph/aff, sensitivity, links, corrections, profile evidence).
 * Novelty/repetition/recency are computed by MemoryWriter, not asked of the model.
 */
class SignificanceEncoder(private val provider: LLMProvider, private val config: JanesPalaceConfig) {
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun encode(turn: TurnObservation, recent: List<Memory>): EncoderVerdict? {
        var text = completeText(buildPrompt(turn, recent)) ?: return null
        parse(text)?.let { return it }
        text = completeText("Respond with ONLY the JSON object, no prose.\n\n" + buildPrompt(turn, recent))
            ?: return null
        return parse(text)
    }

    internal fun buildPrompt(turn: TurnObservation, recent: List<Memory>): String = buildString {
        appendLine("You maintain long-term memory for a personal assistant. Decide what from this")
        appendLine("exchange deserves remembering. Respond with ONLY a JSON object:")
        appendLine("""{"memories":[{"text":"normalized third-person fact","room":"ENTITIES|TASKS|EPISODES|KNOWLEDGE|NARRATIVE",""")
        appendLine(""" "emph":0.0,"aff":0.0,"sensitivity":"PUBLIC|PERSONAL|SENSITIVE|RESTRICTED",""")
        appendLine(""" "provenance":"USER_DIRECT|USER_ARTIFACT|THIRD_PARTY|SYSTEM_INFERRED",""")
        appendLine(""" "causedBy":["<existing memory id>"],"thread":"short thread label or null","supersedes":"<id or null>"}],""")
        appendLine(""" "profile":[{"path":"dotted.trait.path","value":"..."}]}""")
        appendLine()
        appendLine("Rules:")
        appendLine("- Emit [] for trivial exchanges (small talk, generic Q&A). Most turns store NOTHING.")
        appendLine("- emph: did the user stress it or say to remember it (0..1)? aff: emotional weight (0..1).")
        appendLine("- Rooms: ENTITIES people/orgs/pets/places; TASKS errands/appointments/deadlines;")
        appendLine("  EPISODES events/decisions reported; KNOWLEDGE durable facts of the user's world;")
        appendLine("  NARRATIVE only for explicit cause-effect story beats.")
        appendLine("- NEVER store credentials, passwords, payment card or government ID numbers.")
        appendLine("- Facts about third parties' health/legal matters: generalized form only.")
        appendLine("- 'RESTRICTED' only when the user says to keep it private.")
        appendLine("- causedBy/supersedes may only reference ids listed below.")
        appendLine("- profile: stable user traits only (names, preferences, recurring patterns).")
        appendLine()
        appendLine("## Existing recent memories")
        appendLine(recent.joinToString("\n") { "- [${it.id}] (${it.room}) ${it.text}" }.ifEmpty { "(none)" })
        appendLine()
        appendLine("## Exchange")
        appendLine("USER: ${turn.userInput.take(2000)}")
        appendLine("ASSISTANT: ${turn.assistantReply.take(2000)}")
    }

    internal fun parse(text: String): EncoderVerdict? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString(EncoderVerdict.serializer(), text.substring(start, end + 1))
        }.getOrNull()
    }

    private suspend fun completeText(prompt: String): String? =
        when (val r = runCatching {
            provider.complete(CompletionRequest(
                messages = listOf(Message(MessageRole.USER, prompt)),
                model = config.encoderModel ?: config.sessionModel ?: return null,
                maxTokens = config.encoderMaxTokens, temperature = 0.0))
        }.getOrNull()) {
            is LLMResponse.Text -> r.content
            else -> null
        }
}
