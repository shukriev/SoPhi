package dev.sophi.memory.jane

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.system1.JudgmentSpec
import dev.sophi.ai.system1.Judgments
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The encoder's input as evaluation and training see it: only the fields the prompt reads. */
@Serializable
internal data class EncoderTurn(
    val userInput: String,
    val assistantReply: String = "",
    val ambient: Boolean = false,
    val inMeeting: Boolean = false
)

@Serializable
internal data class RecentMemory(val id: String, val room: String, val text: String)

@Serializable
internal data class EncoderInput(val turn: EncoderTurn, val recent: List<RecentMemory> = emptyList())

private val lenientJson = Json { ignoreUnknownKeys = true }
private val canonicalJson = Json { encodeDefaults = true }

internal fun encoderPrompt(userInput: String, assistantReply: String, ambient: Boolean, recent: List<RecentMemory>): String =
    buildString {
    if (ambient) {
        appendLine("You maintain long-term memory for a personal assistant. The exchange below is")
        appendLine("overheard ambient conversation, not directed at you — possibly multiple speakers,")
        appendLine("and 'ASSISTANT' will be empty. Decide what deserves remembering. Respond with ONLY")
        appendLine("a JSON object:")
    } else {
        appendLine("You maintain long-term memory for a personal assistant. Decide what from this")
        appendLine("exchange deserves remembering. Respond with ONLY a JSON object:")
    }
    appendLine("""{"memories":[{"text":"normalized third-person fact, naming who it is about","room":"ENTITIES|TASKS|EPISODES|KNOWLEDGE|NARRATIVE",""")
    appendLine(""" "emph":0.0,"aff":0.0,"dur":0.0,"commitment":false,"sensitivity":"PUBLIC|PERSONAL|SENSITIVE|RESTRICTED",""")
    appendLine(""" "provenance":"USER_DIRECT|USER_ARTIFACT|THIRD_PARTY|SYSTEM_INFERRED",""")
    appendLine(""" "causedBy":["<existing memory id>"],"thread":"short thread label or null","supersedes":"<id or null>"}],""")
    appendLine(""" "profile":[{"path":"dotted.trait.path","value":"...","explicit":false}]}""")
    appendLine()
    appendLine("Rules:")
    // Ambient answers carry "meeting", so a bare [] there would drop it.
    if (ambient) appendLine("- Use \"memories\":[] for trivial talk (small talk, TV, generic chatter). Most turns store NOTHING.")
    else appendLine("- Emit [] for trivial exchanges (small talk, generic Q&A). Most turns store NOTHING.")
    appendLine("- emph: did the user stress it or say to remember it (0..1)? aff: emotional weight (0..1).")
    appendLine("- dur: will this still be true and useful a month from now (0..1)? High for facts about the")
    appendLine("  user's identity, people, work, places, long-running projects, and conventions or ways their")
    appendLine("  team works (naming rules, processes); low for details of the")
    appendLine("  current task (paths, settings, file names, one-off values).")
    appendLine("- commitment: true when the user states they will do something for someone else or")
    appendLine("  themselves — a promise or obligation (\"I'll call him back\", \"I need to renew my")
    appendLine("  passport\") — not a bare fact or something already done.")
    appendLine("- profile.explicit: true ONLY if the user directly asked you to remember/note this fact")
    appendLine("  (e.g. \"remember that I...\", \"please note...\"), not merely mentioned it in passing.")
    appendLine("- Rooms: ENTITIES people/orgs/pets/places; TASKS errands/appointments/deadlines;")
    appendLine("  EPISODES events/decisions reported; KNOWLEDGE durable facts of the user's world;")
    appendLine("  NARRATIVE only for explicit cause-effect story beats.")
    if (ambient) {
        appendLine("- meeting: also give a top-level \"meeting\" (0..1): how much this sounds like a meeting —")
        appendLine("  several people working through an agenda, decisions, or who does what. 0 for TV, radio,")
        appendLine("  small talk or one person talking. Always answer with the object here, e.g.")
        appendLine("  {\"meeting\":0.0,\"memories\":[]}, even when nothing deserves remembering.")
        appendLine("- provenance: THIRD_PARTY when the content is about someone other than the user")
        appendLine("  (a named third party speaking, or being spoken about); USER_DIRECT only when the")
        appendLine("  user is clearly the one speaking about themselves. If you cannot tell who is")
        appendLine("  speaking, say THIRD_PARTY — never guess USER_DIRECT. Prefer room ENTITIES for")
        appendLine("  facts about a named person.")
        appendLine("- The SUBJECT of \"text\" must match its provenance. A THIRD_PARTY memory has to say")
        appendLine("  who it is really about — a named person, or a description like \"an interview")
        appendLine("  candidate\" or \"a colleague in the meeting\". NEVER begin a THIRD_PARTY memory with")
        appendLine("  \"The user ...\": when someone else describes their own job, project or plans, that")
        appendLine("  is a fact about THEM. Tagging provenance correctly and still writing \"the user\"")
        appendLine("  stores a false fact about the user, which is worse than storing nothing.")
        appendLine("- A microphone cannot tell a person in the room from a television. If the text reads")
        appendLine("  like recorded or broadcast media rather than people talking to each other —")
        appendLine("  film or TV dialogue, a news or sports broadcast, a podcast or interview, song")
        appendLine("  lyrics, an advertisement, a lecture or audiobook — then it is not about the")
        appendLine("  user's life. Store nothing from it, however dramatic it sounds.")
    }
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
    appendLine("USER: ${userInput.take(2000)}")
    appendLine("ASSISTANT: ${assistantReply.take(2000)}")
    }

internal fun encoderRequest(prompt: String, model: String, maxTokens: Int) = CompletionRequest(
    messages = listOf(Message(MessageRole.USER, prompt)),
    model = model, maxTokens = maxTokens, temperature = 0.0,
    // This is structured extraction, not a task that benefits from chain-of-thought;
    // reasoning models otherwise burn the whole token budget "thinking" and never emit JSON.
    reasoningEffort = "none",
    judgment = Judgments.ENCODER
)

internal fun parseEncoderVerdict(text: String): EncoderVerdict? {
    val trimmed = text.trim()
    // The prompt tells the model to "Emit [] for trivial exchanges" — models sometimes take that
    // literally instead of wrapping it as {"memories":[],"profile":[]}. Both mean the same thing.
    // An empty reply is no answer (a thinking model can spend its whole budget reasoning), not
    // "nothing to store": returning null makes encode() retry, then report it.
    if (trimmed.isEmpty()) return null
    if (trimmed == "[]") return EncoderVerdict()
    val start = text.indexOf('{'); val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching {
        lenientJson.decodeFromString(EncoderVerdict.serializer(), text.substring(start, end + 1))
    }.getOrNull()
}

/** The encoder judgment (ADR-042 addendum): the one copy of its prompt, request and parser. */
object EncoderJudgment : JudgmentSpec {
    override val name = Judgments.ENCODER

    override fun render(inputJson: String, model: String): CompletionRequest {
        val input = lenientJson.decodeFromString(EncoderInput.serializer(), inputJson)
        val prompt = encoderPrompt(input.turn.userInput, input.turn.assistantReply, input.turn.ambient, input.recent)
        return encoderRequest(prompt, model, JanesPalaceConfig().encoderMaxTokens)
    }

    override fun parse(reply: String): String? =
        parseEncoderVerdict(reply)?.let { canonicalJson.encodeToString(EncoderVerdict.serializer(), it) }
}
