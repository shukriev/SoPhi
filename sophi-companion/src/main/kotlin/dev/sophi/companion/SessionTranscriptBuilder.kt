package dev.sophi.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Builds a live, human-readable transcript from a turn's streaming events — shared by
 * CompanionRuntime (local sessions, driven by TurnEvent) and RemoteSessionRegistry (CLI
 * sessions, driven by HubEvent). Tokens accumulate into a single replacing line rather than
 * one transcript entry per token; tool calls append discrete lines and end the current
 * reasoning/answer segment, so the next tokens after a tool call start a fresh line.
 */
class SessionTranscriptBuilder {
    private val state = MutableStateFlow<List<String>>(emptyList())
    val transcript: StateFlow<List<String>> = state

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
