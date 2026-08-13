package dev.sophi.companion

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds a live, human-readable transcript from a turn's streaming events — shared by
 * CompanionRuntime (local sessions, driven by TurnEvent) and RemoteSessionRegistry (CLI
 * sessions, driven by HubEvent). Tokens accumulate into a single replacing line rather than
 * one transcript entry per token; tool calls append discrete lines and end the current
 * reasoning/answer segment, so the next tokens after a tool call start a fresh line.
 */
class SessionTranscriptBuilder {
    companion object {
        /**
         * Same line shapes as the live streaming path, rebuilt from a session's persisted
         * entries (AgentLoop.kt: ASSISTANT entries with a "toolCalls" metadata JSON array carry
         * no content of their own, TOOL_RESULT entries carry a "toolName"). Reasoning tokens
         * aren't persisted at all, so replayed turns never show a "(thinking)" line.
         */
        fun linesFor(entries: List<SessionEntry>): List<String> = entries.flatMap { entry ->
            when (entry.role) {
                EntryRole.USER -> listOf("you: ${entry.content}")
                EntryRole.ASSISTANT -> {
                    val toolCallsJson = entry.metadata["toolCalls"]
                    when {
                        toolCallsJson != null -> Json.parseToJsonElement(toolCallsJson).jsonArray.map { call ->
                            val obj = call.jsonObject
                            val name = obj["name"]?.jsonPrimitive?.content ?: "?"
                            val args = obj["argumentsJson"]?.jsonPrimitive?.content ?: "{}"
                            "sophi (tool): $name($args)"
                        }
                        entry.content.isNotEmpty() -> listOf("sophi: ${entry.content}")
                        else -> emptyList()
                    }
                }
                EntryRole.TOOL_RESULT -> listOf("sophi (tool result): ${entry.metadata["toolName"] ?: "?"} -> ${entry.content}")
                EntryRole.SYSTEM -> emptyList()
            }
        }
    }

    private val state = MutableStateFlow<List<String>>(emptyList())
    val transcript: StateFlow<List<String>> = state

    /** Prepends persisted history ahead of any live turns. No-op once the transcript is non-empty. */
    fun seed(lines: List<String>) {
        if (state.value.isEmpty()) state.value = lines
    }

    private var reasoningBuffer: StringBuilder? = null
    private var answerBuffer: StringBuilder? = null
    private var reasoningLineIndex: Int? = null
    private var answerLineIndex: Int? = null

    fun startTurn(userInput: String) {
        append("you: $userInput")
        resetStreamingState()
    }

    fun endTurn() {
        resetStreamingState()
    }

    fun onToken(text: String) {
        val buf = (answerBuffer ?: StringBuilder().also { answerBuffer = it }).append(text)
        val idx = answerLineIndex
        if (idx == null) {
            append("sophi: $buf")
            answerLineIndex = state.value.lastIndex
        } else {
            replaceAt(idx, "sophi: $buf")
        }
    }

    fun onReasoningToken(text: String) {
        val buf = (reasoningBuffer ?: StringBuilder().also { reasoningBuffer = it }).append(text)
        val idx = reasoningLineIndex
        if (idx == null) {
            append("sophi (thinking): $buf")
            reasoningLineIndex = state.value.lastIndex
        } else {
            replaceAt(idx, "sophi (thinking): $buf")
        }
    }

    fun onToolCallStarted(name: String, argsJson: String) {
        append("sophi (tool): $name($argsJson)")
        // A tool round ends the current segment — the next tokens after this belong to a new
        // segment, not a continuation of whatever came before.
        resetStreamingState()
    }

    fun onToolCallFinished(name: String, result: String, isError: Boolean) {
        val outcome = if (isError) "ERROR: $result" else result
        append("sophi (tool result): $name -> $outcome")
    }

    private fun append(line: String) {
        state.value = state.value + line
    }

    private fun replaceAt(index: Int, line: String) {
        val current = state.value
        if (index in current.indices) state.value = current.toMutableList().also { it[index] = line }
    }

    private fun resetStreamingState() {
        reasoningBuffer = null
        answerBuffer = null
        reasoningLineIndex = null
        answerLineIndex = null
    }
}
