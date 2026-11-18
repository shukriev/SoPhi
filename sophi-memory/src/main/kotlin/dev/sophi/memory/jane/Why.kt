package dev.sophi.memory.jane

import dev.sophi.memory.TurnObservation
import java.time.Instant
import java.time.ZoneId

/** How much of the user's message a memory keeps as its source. */
const val EXCERPT_CHARS = 280

internal fun originOf(turn: TurnObservation): Origin = when {
    turn.inMeeting -> Origin.MEETING
    turn.ambient -> Origin.AMBIENT
    else -> Origin.CHAT
}

/** The user's own words, from a chat only: overheard speech is a bystander's and stays out of storage. */
internal fun excerptOf(turn: TurnObservation): String? =
    if (turn.ambient || turn.inMeeting) null else redact(turn.userInput).trim().take(EXCERPT_CHARS).ifEmpty { null }

/**
 * high: the user said it in chat or confirmed it; medium: inferred, or from a file or page; low: overheard,
 * or about someone else. Staleness is separate (recall's VERIFY marker).
 */
internal fun Memory.confidence(): String = when {
    provenance == Provenance.THIRD_PARTY || origin == Origin.AMBIENT || origin == Origin.MEETING -> "low"
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
        Origin.CHAT -> "you said it in a chat (session $sourceSessionId)"
        Origin.AMBIENT -> "overheard by ambient listening"
        Origin.MEETING -> "heard during a meeting"
        Origin.CONFIRMED -> "you confirmed it (session $sourceSessionId)"
        Origin.SHARED -> "shared into the household by ${sharedBy.joinToString(", ").ifEmpty { "a member" }}"
        null -> "saved before SoPhi recorded where memories came from (session $sourceSessionId)"
    }
    appendLine("Where it came from: $from")
    excerpt?.let { appendLine("Your words: \"$it\"") }
    appendLine("Stated or inferred: " + when (provenance) {
        Provenance.USER_DIRECT -> "stated by you"
        Provenance.USER_ARTIFACT -> "taken from a file or page you shared"
        Provenance.THIRD_PARTY -> "about or from someone else, not said by you about yourself"
        Provenance.SYSTEM_INFERRED -> "inferred by SoPhi, not said outright"
    })
    appendLine("Confidence: ${confidence()}")
    appendLine("Saved: ${day(createdAt)}; last confirmed by a later mention: ${day(reinforcedAt)}")
    appendLine("Sensitivity: ${sensitivity.name.lowercase()}")
    append("State: " + when {
        supersededBy != null -> "replaced by a newer memory"
        softDeletedAt != null -> "forgotten (can still be undone)"
        else -> "active"
    })
}
