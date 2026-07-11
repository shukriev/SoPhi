package dev.sophi.learning

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry

object TrajectoryRenderer {
    fun render(entries: List<SessionEntry>, budgetTokens: Int): String {
        val lines = entries.mapIndexed { i, e ->
            val prefix = "[#$i] "
            when {
                e.metadata.containsKey("toolCalls") ->
                    prefix + "[tool call] " + toolNames(e.metadata.getValue("toolCalls")) + " " +
                        e.metadata.getValue("toolCalls").take(300)
                e.role == EntryRole.TOOL_RESULT ->
                    prefix + "[tool result ${e.metadata["toolName"] ?: ""}]: ${e.content.take(300)}"
                else -> "$prefix${e.role}: ${e.content}"
            }
        }
        val budgetChars = budgetTokens * 4
        val full = lines.joinToString("\n")
        if (full.length <= budgetChars) return full

        var headEnd = 0; var used = 0
        while (headEnd < lines.size && used + lines[headEnd].length < budgetChars * 4 / 10) {
            used += lines[headEnd].length; headEnd++
        }
        var tailStart = lines.size; used = 0
        while (tailStart > headEnd && used + lines[tailStart - 1].length < budgetChars * 4 / 10) {
            used += lines[tailStart - 1].length; tailStart--
        }
        return (lines.take(headEnd) +
            "[... ${tailStart - headEnd} entries elided ...]" +
            lines.drop(tailStart)).joinToString("\n")
    }

    private fun toolNames(toolCallsJson: String): String =
        Regex("\"name\":\"([^\"]+)\"").findAll(toolCallsJson).joinToString(",") { it.groupValues[1] }
}
