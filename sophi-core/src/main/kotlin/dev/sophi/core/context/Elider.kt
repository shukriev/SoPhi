package dev.sophi.core.context

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole

/** Stubs tool output from rounds older than the last few — no LLM call, call/result pairing kept. */
object Elider {
    const val PREFIX = "[elided: "

    /** Returns how many TOOL messages were replaced. Round detection matches AgentLoop.compactInPlace. */
    fun elide(messages: MutableList<Message>, from: Int, keepRecentRounds: Int): Int {
        val rounds = messages.indices.filter { i ->
            i >= from && messages[i].role == MessageRole.ASSISTANT && messages[i].toolCalls != null
        }
        if (rounds.size <= keepRecentRounds) return 0
        val cut = if (keepRecentRounds <= 0) messages.size else rounds[rounds.size - keepRecentRounds]
        val calls = messages.flatMap { it.toolCalls.orEmpty() }.associateBy { it.id }
        var count = 0
        for (i in from until cut) {
            val m = messages[i]
            if (m.role != MessageRole.TOOL || m.content.startsWith(PREFIX)) continue
            val call = calls[m.toolCallId]
            val name = m.toolName ?: call?.name ?: "tool"
            val arg = call?.let { keyArg(name, it.argumentsJson) }?.take(80)?.let { " $it" } ?: ""
            val stub = "$PREFIX$name$arg, ${m.content.lines().size} lines — re-read if needed]"
            // A stub longer than a short result would grow the context.
            if (stub.length >= m.content.length) continue
            messages[i] = m.copy(content = stub)
            count++
        }
        return count
    }
}
