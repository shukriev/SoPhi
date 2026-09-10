package dev.sophi.companion.voice

private val HALLUCINATION_DENYLIST = setOf(
    "you", "thank you", "thanks for watching", "[blank_audio]"
)

private val REMINDER_KEYWORDS = listOf(
    "remind me", "don't forget", "reminder", "remember to", "tomorrow", "later today"
)

private const val MAX_CHUNK_CHARS = 1800

data class AmbientBatch(val chunks: List<String>, val looksLikeReminder: Boolean)

/**
 * Pure accumulation/segmentation logic for ambient listening — no mic, no LLM. The caller
 * ([dev.sophi.companion.CompanionRuntime.startAmbientListening]) owns the actual recorder,
 * transcriber, and runtime calls; this class only decides what to keep and when to flush.
 */
class AmbientListener(private val flushIntervalMs: Long) {
    private val buffer = StringBuilder()
    private var lastFlushMs: Long? = null

    fun isHallucination(text: String): Boolean =
        text.trim().trim('.', '!', '?').lowercase() in HALLUCINATION_DENYLIST

    fun accumulate(text: String) {
        if (buffer.isNotEmpty()) buffer.append(' ')
        buffer.append(text)
    }

    fun shouldFlush(nowMs: Long): Boolean {
        if (buffer.isEmpty()) return false
        val last = lastFlushMs ?: return true
        return nowMs - last >= flushIntervalMs
    }

    fun flush(nowMs: Long): AmbientBatch? {
        lastFlushMs = nowMs
        if (buffer.isEmpty()) return null
        val text = buffer.toString()
        buffer.clear()
        val looksLikeReminder = REMINDER_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        return AmbientBatch(chunk(text), looksLikeReminder)
    }

    private fun chunk(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (word in text.split(" ")) {
            if (current.isNotEmpty() && current.length + 1 + word.length > MAX_CHUNK_CHARS) {
                chunks.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
}

/**
 * The turn prompt for the ambient reminder-check runtime ([dev.sophi.companion.CompanionRuntime.
 * startAmbientListening]) — mirrors [dev.sophi.companion.buildCheckInPrompt]'s shape.
 */
fun buildAmbientReminderPrompt(transcript: String): String = """
    Ambient conversation (not directed at you) may contain a reminder or task
    request. If so, call manage_scheduled_task to create it. If not, do nothing
    and reply with nothing.

    Transcript: $transcript
""".trimIndent()
