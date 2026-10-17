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

    private val FILE_TOOLS = setOf("read_file", "read_context", "grep", "glob", "search_context")

    /**
     * Stubs the largest TOOL results at or after [from] (the newest round included) until [charsToSave] are saved,
     * file-reading tools first. Results the model hasn't seen yet (after the last tool-call message) get a "too large" stub.
     * Returns chars saved.
     */
    fun elideLargest(messages: MutableList<Message>, from: Int, charsToSave: Int): Int {
        val calls = messages.flatMap { it.toolCalls.orEmpty() }.associateBy { it.id }
        val lastRound = messages.indexOfLast { it.role == MessageRole.ASSISTANT && it.toolCalls != null }
        var saved = 0
        val order = (from until messages.size)
            .filter { messages[it].role == MessageRole.TOOL && !messages[it].content.startsWith(PREFIX) }
            .sortedWith(compareBy<Int> { if ((messages[it].toolName ?: calls[messages[it].toolCallId]?.name) in FILE_TOOLS) 0 else 1 }
                .thenByDescending { messages[it].content.length })
        for (i in order) {
            if (saved >= charsToSave) break
            val m = messages[i]
            val stub = stubFor(m, calls, unseen = i > lastRound) ?: continue
            saved += m.content.length - stub.length
            messages[i] = m.copy(content = stub)
        }
        return saved
    }

    /** The stub for a TOOL message, or null when it would not be shorter than the result. */
    private fun stubFor(m: Message, calls: Map<String, ToolCall>, unseen: Boolean = false): String? {
        val call = calls[m.toolCallId]
        val name = m.toolName ?: call?.name ?: "tool"
        val arg = call?.let { keyArg(name, it.argumentsJson) }?.take(80)?.let { " $it" } ?: ""
        val tail = if (unseen) "too large to show at once; re-read a smaller part or narrow the request]" else "re-read if needed]"
        val stub = "$PREFIX$name$arg, ${m.content.lines().size} lines — $tail"
        return stub.takeIf { it.length < m.content.length }
    }
}
