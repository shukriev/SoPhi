package dev.sophi.memory.jane

import dev.sophi.memory.TurnObservation
import java.time.Instant
import java.time.ZoneId

internal fun originOf(turn: TurnObservation): Origin = when {
    turn.inMeeting -> Origin.MEETING
    turn.ambient -> Origin.AMBIENT
    else -> Origin.CHAT
}

/**
 * high: the user said it in chat or confirmed it; medium: inferred, or from a file or page; low: overheard,
 * or about someone else (a summary with an overheard part counts as overheard). Staleness is separate (recall's VERIFY marker).
 */
internal fun Memory.confidence(): String = when {
    provenance == Provenance.THIRD_PARTY || origin == Origin.AMBIENT || origin == Origin.MEETING ||
        "ambient" in sourceSessionId.split(",") -> "low"
    provenance == Provenance.SYSTEM_INFERRED || provenance == Provenance.USER_ARTIFACT -> "medium"
    else -> "high"
}

private fun Memory.how(): String = when (provenance) {
    Provenance.USER_DIRECT -> "stated"
    Provenance.USER_ARTIFACT -> "from a file or page"
    Provenance.THIRD_PARTY -> "about someone else"
    Provenance.SYSTEM_INFERRED -> "inferred"
}

private fun Memory.where(): String? = when (origin) {
    Origin.CHAT -> "in chat"
    Origin.AMBIENT -> "overheard"
    Origin.MEETING -> "in a meeting"
    Origin.CONFIRMED -> "confirmed by the user"
    Origin.SHARED -> "shared by the household"
    null -> null
}

/** The short source tag recall puts on each memory line: "stated in chat", "about someone else, overheard". */
internal fun Memory.sourceLabel(): String = listOfNotNull(how(), where()).joinToString(if (provenance == Provenance.USER_DIRECT) " " else ", ")

private fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/** "Why do you believe this?" for one memory, in words the model can pass on to the user. */
internal fun Memory.explain(): String = buildString {
    appendLine("Memory: $text")
    val from = when (origin) {
        // Not "you said": SoPhi sends its own prompts (Plan my day, Do it) through chats too.
        Origin.CHAT -> "from a chat (session $sourceSessionId)"
        Origin.AMBIENT -> "overheard by ambient listening"
        Origin.MEETING -> "heard during a meeting"
        Origin.CONFIRMED -> "you confirmed it (session $sourceSessionId)"
        Origin.SHARED -> "shared into the household by ${sharedBy.joinToString(", ").ifEmpty { "a member" }}"
        null -> "summarized from older memories, or saved before sources were recorded (session $sourceSessionId)"
    }
    appendLine("Where it came from: $from")
    appendLine("Stated or inferred: " + when (provenance) {
        Provenance.USER_DIRECT -> if (origin == Origin.SHARED) "stated by ${sharedBy.joinToString(", ").ifEmpty { "a member" }}" else "stated by you"
        Provenance.USER_ARTIFACT -> "taken from a file or page you shared"
        Provenance.THIRD_PARTY -> "about or from someone else, not said by you about yourself"
        Provenance.SYSTEM_INFERRED -> "inferred by SoPhi, not said outright"
    })
    appendLine("Confidence: ${confidence()}")
    appendLine("Saved: ${day(createdAt)}; last confirmed by a later mention: ${day(reinforcedAt)}")
    append("Sensitivity: ${sensitivity.name.lowercase()}")
}
