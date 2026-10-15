package dev.sophi.memory.jane

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.memory.TurnObservation
import kotlinx.serialization.Serializable

@Serializable
internal data class VerdictMemory(
    val text: String,
    val room: String,
    val emph: Double = 0.0,
    val aff: Double = 0.0,
    // Nullable on purpose: "the model didn't say" falls back by room (MemoryWriter.durOf); it must
    // not read as "the model said 0", which would drop every durable fact the model forgot to rate.
    val dur: Double? = null,
    val commitment: Boolean = false,
    val sensitivity: String = "PERSONAL",
    // Nullable with no default on purpose: "the model didn't say" and "the model said USER_DIRECT"
    // must stay distinguishable, because MemoryWriter resolves the unstated case differently on an
    // ambient turn (THIRD_PARTY) than on a chat turn (USER_DIRECT).
    val provenance: String? = null,
    val causedBy: List<String> = emptyList(),
    val thread: String? = null,
    val supersedes: String? = null
)

@Serializable
internal data class VerdictProfile(val path: String, val value: String, val explicit: Boolean = false)

@Serializable
internal data class EncoderVerdict(
    val memories: List<VerdictMemory> = emptyList(),
    val profile: List<VerdictProfile> = emptyList(),
    /** Ambient only: how much the text sounds like a meeting (0..1); null = not said (counts as 0). */
    val meeting: Double? = null
)

/**
 * The per-turn encoding judge (spec §7). One structured-output call; judges what only an
 * LLM can (significance, room, emph/aff, sensitivity, links, corrections, profile evidence).
 * Novelty/repetition/recency are computed by MemoryWriter, not asked of the model.
 */
class SignificanceEncoder(
    private val provider: LLMProvider,
    private val config: JanesPalaceConfig,
    private val onWarning: (String) -> Unit = {}
) {
    internal suspend fun encode(turn: TurnObservation, recent: List<Memory>): EncoderVerdict? {
        val first = completeReply(buildPrompt(turn, recent))
        if (first == null) {
            onWarning("memory: encoder call failed — this turn was not evaluated for memory")
            return null
        }
        parse(first.content)?.let { return it }
        // Nothing before the token limit: the identical retry would run out again.
        if (first.content.isBlank() && first.stopReason == "length") {
            onWarning("memory: encoder hit its ${config.encoderMaxTokens}-token limit with no answer — this turn was not evaluated for memory")
            return null
        }
        val text = completeText("Respond with ONLY the JSON object, no prose.\n\n" + buildPrompt(turn, recent))
        if (text == null) {
            onWarning("memory: encoder call failed on retry — this turn was not evaluated for memory")
            return null
        }
        return parse(text) ?: run {
            onWarning("memory: encoder returned output that didn't match the expected schema " +
                "(truncated, malformed JSON, or a wrong field type) — this turn was not evaluated for memory")
            null
        }
    }

    internal fun buildPrompt(turn: TurnObservation, recent: List<Memory>): String =
        encoderPrompt(turn.userInput, turn.assistantReply, turn.ambient, recent.map { RecentMemory(it.id, it.room.name, it.text) })

    internal fun parse(text: String): EncoderVerdict? = parseEncoderVerdict(text)

    private suspend fun completeText(prompt: String): String? = completeReply(prompt)?.content

    private suspend fun completeReply(prompt: String): LLMResponse.Text? =
        when (val r = runCatching {
            provider.complete(encoderRequest(prompt, config.encoderModel ?: config.sessionModel ?: return null, config.encoderMaxTokens))
        }.getOrNull()) {
            is LLMResponse.Text -> r
            else -> null
        }
}
