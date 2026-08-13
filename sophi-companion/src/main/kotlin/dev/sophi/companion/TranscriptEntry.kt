package dev.sophi.companion

/**
 * One entry in a turn's transcript. `id` is assigned by whichever builder created the entry
 * (monotonic for live turns, emission order for replayed history) and never changes — it's how
 * the UI keys Compose items and tracks per-entry expand/collapse state across content updates
 * (a streamed Answer/Reasoning entry's text grows in place at the same id; a ToolInvocation's
 * result is filled in later at the same id).
 */
sealed class TranscriptEntry {
    abstract val id: Int

    data class UserMessage(override val id: Int, val text: String) : TranscriptEntry()
    data class Answer(override val id: Int, val text: String) : TranscriptEntry()
    data class Reasoning(override val id: Int, val text: String) : TranscriptEntry()
    data class ToolInvocation(
        override val id: Int,
        val name: String,
        val argsJson: String,
        val result: String? = null,
        val isError: Boolean = false,
    ) : TranscriptEntry()
}
