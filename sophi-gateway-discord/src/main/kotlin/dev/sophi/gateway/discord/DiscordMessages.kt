package dev.sophi.gateway.discord

import dev.sophi.gateway.GatewayOut
import dev.sophi.gateway.GatewayResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Discord's own JSON: its `type` fields must be written even though they are defaults. */
internal val discordJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Serializable
data class Button(
    val style: Int,
    val label: String,
    @SerialName("custom_id") val customId: String,
    val type: Int = 2,
)

@Serializable
data class ActionRow(val components: List<Button>, val type: Int = 1)

@Serializable
data class OutgoingMessage(val content: String, val components: List<ActionRow> = emptyList())

const val ENDED_ON_MAC = "This request ended on the Mac."

private const val PART_SUFFIX_MAX = 8      // "\n(99/99)"
private const val FENCE_CLOSE = "\n```"
private const val LATE_AFTER_MS = 120_000L
/** Room left for the "sent when your Mac woke" line. */
private const val PLAN_MAX = 1900

/**
 * [text] in parts of at most [limit] characters, cut at a blank line, else a newline, else a space.
 * A code fence open at a cut is closed there and reopened, with its language, in the next part.
 */
fun splitForDiscord(text: String, limit: Int = 2000): List<String> {
    if (text.length <= limit) return listOf(text)
    val parts = mutableListOf<String>()
    var rest = text
    var reopen = ""                        // "```lang\n" when the previous part ended inside a fence
    while (rest.isNotEmpty()) {
        val budget = limit - PART_SUFFIX_MAX - FENCE_CLOSE.length - reopen.length
        val (chunk, next) = if (rest.length <= budget) rest to "" else cut(rest, budget)
        val body = reopen + chunk
        val openLang = openFenceLanguage(body)
        parts += if (openLang != null) body + FENCE_CLOSE else body
        reopen = if (openLang != null) "```$openLang\n" else ""
        rest = next
    }
    return parts.mapIndexed { i, p -> "$p\n(${i + 1}/${parts.size})" }
}

private fun cut(s: String, budget: Int): Pair<String, String> {
    val window = s.substring(0, budget)
    for ((sep, skip) in listOf("\n\n" to 2, "\n" to 1, " " to 1)) {
        val at = window.lastIndexOf(sep)
        if (at > 0) return s.substring(0, at) to s.substring(at + skip)
    }
    return window to s.substring(budget)
}

/** The language of a fence still open at the end of [s] ("" for a bare fence), or null. */
private fun openFenceLanguage(s: String): String? {
    var open: String? = null
    s.lineSequence().map { it.trim() }.filter { it.startsWith("```") }.forEach { line ->
        open = if (open == null) line.removePrefix("```").trim() else null
    }
    return open
}

private fun hhmm(ms: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms))

/** What [out] looks like on Discord. [GatewayOut.Resolved] is an edit of an earlier message, not a message. */
fun render(out: GatewayOut, nowMs: Long, zone: ZoneId): List<OutgoingMessage> {
    val late = if (nowMs - out.createdAtMs > LATE_AFTER_MS) " (due ${hhmm(out.createdAtMs, zone)}, sent when your Mac woke)" else ""
    val messages = when (out) {
        is GatewayOut.Reply -> splitForDiscord(out.text, 2000 - late.length).map { OutgoingMessage(it) }
        is GatewayOut.Permission -> listOf(
            if (out.canAnswer) OutgoingMessage(
                "**SoPhi asks** · ${out.chatTitle}\n${out.action}" +
                    if (out.tainted) "\nThis chat has outside text in it, so each step asks." else "",
                listOf(ActionRow(listOf(Button(3, "Allow", "p:${out.key}:allow"), Button(4, "Deny", "p:${out.key}:deny")))),
            ) else OutgoingMessage("**SoPhi needs you on your Mac** · ${out.chatTitle}: ${out.action}."),
        )
        is GatewayOut.Plan -> listOf(planMessage(out))
        is GatewayOut.Question -> listOf(
            OutgoingMessage(
                out.text + if (out.allowFreeText) "\nOr reply in your own words." else "",
                out.choices.take(25).mapIndexed { i, c -> Button(2, "${'A' + i}  $c".take(80), "q:${out.key}:$i") }
                    .chunked(5).map { ActionRow(it) },
            ),
        )
        is GatewayOut.Notice -> listOf(OutgoingMessage("**${out.title}** · ${out.body}"))
        is GatewayOut.Resolved -> emptyList()
    }
    return if (late.isEmpty() || messages.isEmpty()) messages
    else messages.dropLast(1) + messages.last().let { it.copy(content = it.content + late) }
}

/** Every step in full, or none: a plan that doesn't fit one message is approved on the Mac. */
private fun planMessage(out: GatewayOut.Plan): OutgoingMessage {
    val full = "**SoPhi has a plan** · ${out.chatTitle}\n${out.task}\n" +
        out.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n") + "\nNothing has run yet. To leave a step out, use your Mac."
    if (out.canAnswer && full.length <= PLAN_MAX) return OutgoingMessage(
        full, listOf(ActionRow(listOf(Button(3, "Approve", "pl:${out.key}:approve"), Button(4, "Cancel", "pl:${out.key}:cancel")))),
    )
    val changes = if (out.steps.size == 1) "1 change" else "${out.steps.size} changes"
    return OutgoingMessage("**SoPhi has a plan for you on your Mac** · ${out.chatTitle}: $changes · ${out.task}.")
}

/** What a clicked card is edited to, from what the host decided. */
fun clickText(result: GatewayResult, choiceLabel: String?, nowMs: Long, zone: ZoneId, plan: Boolean = false): String = when (result) {
    is GatewayResult.Answered -> when (result.allow) {
        true -> "${if (plan) "Approved" else "Allowed"} from your phone · ${hhmm(nowMs, zone)}"
        false -> "${if (plan) "Cancelled" else "Denied"} from your phone · ${hhmm(nowMs, zone)}"
        null -> choiceLabel.orEmpty()
    }
    GatewayResult.AlreadyAnswered -> "Answered on the Mac"
    GatewayResult.NotAllowed -> "Approve this on your Mac."
    is GatewayResult.Sent, GatewayResult.NewChatStarted -> ""
}
