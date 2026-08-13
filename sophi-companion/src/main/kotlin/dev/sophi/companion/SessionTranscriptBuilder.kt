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
 * Builds a live, structured transcript from a turn's streaming events — shared by
 * CompanionRuntime (local sessions, driven by TurnEvent) and RemoteSessionRegistry (CLI
 * sessions, driven by HubEvent). Tokens accumulate into a single entry that's replaced in
 * place as more text streams in, rather than one entry per token; a tool call's Started and
 * Finished events merge into one ToolInvocation entry. A tool round ends the current
 * reasoning/answer segment, so tokens after it start a fresh entry.
 */
class SessionTranscriptBuilder {
    companion object {
        /**
         * Rebuilds a session's persisted entries (AgentLoop.kt: ASSISTANT entries carry a
         * "toolCalls" metadata JSON array of {id, name, argumentsJson}; TOOL_RESULT entries
         * carry that same id in "toolCallId" plus a "toolName") into TranscriptEntry, matching
         * each tool call to its result by that shared id — precise, unlike the live path's
         * FIFO-by-name fallback, because persisted data has a real identity to match on.
         * Reasoning tokens aren't persisted at all, so replayed turns never include a Reasoning
         * entry. isError isn't persisted explicitly, so it's inferred the same way AgentLoop
         * itself treats a tool failure: content starting with "Error: ".
         */
        fun entriesFor(entries: List<SessionEntry>): List<TranscriptEntry> {
            var nextId = 0
            val result = mutableListOf<TranscriptEntry>()
            val invocationIndexByCallId = mutableMapOf<String, Int>()

            for (entry in entries) {
                when (entry.role) {
                    EntryRole.USER -> result += TranscriptEntry.UserMessage(nextId++, entry.content)
                    EntryRole.ASSISTANT -> {
                        val toolCallsJson = entry.metadata["toolCalls"]
                        if (toolCallsJson != null) {
                            Json.parseToJsonElement(toolCallsJson).jsonArray.forEach { call ->
                                val obj = call.jsonObject
                                val callId = obj["id"]?.jsonPrimitive?.content ?: return@forEach
                                val name = obj["name"]?.jsonPrimitive?.content ?: "?"
                                val args = obj["argumentsJson"]?.jsonPrimitive?.content ?: "{}"
                                invocationIndexByCallId[callId] = result.size
                                result += TranscriptEntry.ToolInvocation(nextId++, name, args)
                            }
                        } else if (entry.content.isNotEmpty()) {
                            result += TranscriptEntry.Answer(nextId++, entry.content)
                        }
                    }
                    EntryRole.TOOL_RESULT -> {
                        val index = entry.metadata["toolCallId"]?.let { invocationIndexByCallId[it] }
                        if (index != null) {
                            val invocation = result[index] as TranscriptEntry.ToolInvocation
                            result[index] = invocation.copy(
                                result = entry.content,
                                isError = entry.content.startsWith("Error: ")
                            )
                        }
                    }
                    EntryRole.SYSTEM -> Unit
                }
            }
            return result
        }
    }

    private val state = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = state
    private var nextId = 0

    private var answerId: Int? = null
    private var answerBuffer: StringBuilder? = null
    private var reasoningId: Int? = null
    private var reasoningBuffer: StringBuilder? = null
    // FIFO of in-flight tool invocation ids, per tool name. TurnEvent.ToolCallStarted/Finished
    // (sophi-core) carry only a tool name, no call id, so concurrent same-named calls can only
    // be matched by arrival order — the same ambiguity the prior string-based implementation
    // had (it just appended two unlinked lines). See README known limitations.
    private val pendingToolCalls = mutableMapOf<String, ArrayDeque<Int>>()

    /** Prepends persisted history ahead of any live turns. No-op once the transcript is non-empty. */
    fun seed(entries: List<TranscriptEntry>) {
        if (state.value.isEmpty()) {
            state.value = entries
            nextId = (entries.maxOfOrNull { it.id } ?: -1) + 1
        }
    }

    fun startTurn(userInput: String) {
        append(TranscriptEntry.UserMessage(nextId++, userInput))
        resetStreamingState()
    }

    fun endTurn() {
        resetStreamingState()
        // Any call that started but never got a Finished event (turn errored or was cancelled
        // mid-round) would otherwise sit in the FIFO forever and get incorrectly matched to a
        // later, unrelated turn's call to the same tool name. Under normal completion every
        // started call has already been drained by its matching Finished by the time endTurn
        // runs, so this is a no-op there — it only matters for the aborted-turn case.
        pendingToolCalls.clear()
    }

    fun onToken(text: String) {
        val buf = (answerBuffer ?: StringBuilder().also { answerBuffer = it }).append(text)
        val id = answerId
        if (id == null) {
            val newId = nextId++
            answerId = newId
            append(TranscriptEntry.Answer(newId, buf.toString()))
        } else {
            replaceAt(id) { TranscriptEntry.Answer(id, buf.toString()) }
        }
    }

    fun onReasoningToken(text: String) {
        val buf = (reasoningBuffer ?: StringBuilder().also { reasoningBuffer = it }).append(text)
        val id = reasoningId
        if (id == null) {
            val newId = nextId++
            reasoningId = newId
            append(TranscriptEntry.Reasoning(newId, buf.toString()))
        } else {
            replaceAt(id) { TranscriptEntry.Reasoning(id, buf.toString()) }
        }
    }

    fun onToolCallStarted(name: String, argsJson: String) {
        val id = nextId++
        append(TranscriptEntry.ToolInvocation(id, name, argsJson))
        pendingToolCalls.getOrPut(name) { ArrayDeque() }.addLast(id)
        // A tool round ends the current segment — the next tokens after this belong to a new
        // segment, not a continuation of whatever came before.
        resetStreamingState()
    }

    fun onToolCallFinished(name: String, result: String, isError: Boolean) {
        val id = pendingToolCalls[name]?.removeFirstOrNull() ?: return
        replaceAt(id) { (it as TranscriptEntry.ToolInvocation).copy(result = result, isError = isError) }
    }

    private fun append(entry: TranscriptEntry) {
        state.value = state.value + entry
    }

    // ponytail: id lookup is a linear scan (indexOfFirst) before the existing full-list copy —
    // doubles the per-token work rather than the O(1) a cached index would give, bounded by
    // transcript length. Not worth an id->index cache for realistically sized chat transcripts;
    // revisit if streaming ever visibly lags on long sessions.
    private fun replaceAt(id: Int, transform: (TranscriptEntry) -> TranscriptEntry) {
        val current = state.value
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) state.value = current.toMutableList().also { it[index] = transform(it[index]) }
    }

    private fun resetStreamingState() {
        reasoningBuffer = null
        answerBuffer = null
        reasoningId = null
        answerId = null
    }
}
