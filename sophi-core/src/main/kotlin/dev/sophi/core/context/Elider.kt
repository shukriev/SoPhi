package dev.sophi.core.context

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.ToolCall

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
            val stub = stubFor(m, calls) ?: continue
            messages[i] = m.copy(content = stub)
            count++
        }
        return count
    }

    /** Stubs the largest TOOL results at or after [from] (the newest round included) until [charsToSave] are saved. Returns chars saved. */
    fun elideLargest(messages: MutableList<Message>, from: Int, charsToSave: Int): Int {
        val calls = messages.flatMap { it.toolCalls.orEmpty() }.associateBy { it.id }
        var saved = 0
        val bySize = (from until messages.size)
            .filter { messages[it].role == MessageRole.TOOL && !messages[it].content.startsWith(PREFIX) }
            .sortedByDescending { messages[it].content.length }
        for (i in bySize) {
            if (saved >= charsToSave) break
            val m = messages[i]
            val stub = stubFor(m, calls) ?: continue
            saved += m.content.length - stub.length
            messages[i] = m.copy(content = stub)
        }
        return saved
    }

    /** The stub for a TOOL message, or null when it would not be shorter than the result. */
    private fun stubFor(m: Message, calls: Map<String, ToolCall>): String? {
        val call = calls[m.toolCallId]
        val name = m.toolName ?: call?.name ?: "tool"
        val arg = call?.let { keyArg(name, it.argumentsJson) }?.take(80)?.let { " $it" } ?: ""
        val stub = "$PREFIX$name$arg, ${m.content.lines().size} lines — re-read if needed]"
        return stub.takeIf { it.length < m.content.length }
    }
}
