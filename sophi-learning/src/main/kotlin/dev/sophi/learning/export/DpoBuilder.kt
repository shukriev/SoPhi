package dev.sophi.learning.export

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.json.*

class DpoBuilder(private val redact: (String) -> String) {
    fun build(
        entries: List<SessionEntry>, systemPrompt: String?,
        rejectedIndex: Int, chosenIndex: Int
    ): String? {
        fun isPlainAssistant(i: Int) = i in entries.indices &&
            entries[i].role == EntryRole.ASSISTANT && !entries[i].metadata.containsKey("toolCalls")
        if (!isPlainAssistant(rejectedIndex) || !isPlainAssistant(chosenIndex)) return null
        if (chosenIndex <= rejectedIndex) return null
        val between = entries.subList(rejectedIndex + 1, chosenIndex)
        if (between.any { it.role != EntryRole.USER }) return null   // can't cleanly excise

        val prompt = ChatMessages.fromEntries(entries.take(rejectedIndex), systemPrompt)
        fun assistant(i: Int) = buildJsonArray {
            add(buildJsonObject { put("role", "assistant"); put("content", entries[i].content) })
        }
        val obj = buildJsonObject {
            put("prompt", prompt)
            put("chosen", assistant(chosenIndex))
            put("rejected", assistant(rejectedIndex))
        }
        return obj.redactedWith(redact).toString()
    }
}
