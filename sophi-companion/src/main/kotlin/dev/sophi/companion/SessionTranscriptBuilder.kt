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
    private var reasoningId: Int? = null
    // Some local models (e.g. Qwen3 served via Ollama/vLLM) have no separate reasoning-content
    // field — they emit their thinking inline as <think>...</think> in the ordinary content
    // stream, which onReasoningToken/StreamEvent.Reasoning never sees. rawContentBuffer holds
    // everything onToken has received this segment so splitThink can be re-run on the whole
    // thing each call, so a <think>/</think> boundary split across two chunks is still caught.
    private var rawContentBuffer: StringBuilder? = null
    // Separate from rawContentBuffer: this is for providers with a genuine reasoning-content
    // field (StreamEvent.Reasoning), independent of the inline-<think>-tag path onToken handles.
    private var explicitReasoningBuffer: StringBuilder? = null
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
        val raw = (rawContentBuffer ?: StringBuilder().also { rawContentBuffer = it }).append(text)
        val split = splitThink(raw.toString())

        if (split.reasoning.isNotEmpty()) {
            val id = reasoningId
            if (id == null) {
                val newId = nextId++
                reasoningId = newId
                append(TranscriptEntry.Reasoning(newId, split.reasoning))
            } else {
                replaceAt(id) { TranscriptEntry.Reasoning(id, split.reasoning) }
            }
        }
        if (split.answer.isNotEmpty()) {
            val id = answerId
            if (id == null) {
                val newId = nextId++
                answerId = newId
                append(TranscriptEntry.Answer(newId, split.answer))
            } else {
                replaceAt(id) { TranscriptEntry.Answer(id, split.answer) }
            }
        }
    }

    fun onReasoningToken(text: String) {
        val buf = (explicitReasoningBuffer ?: StringBuilder().also { explicitReasoningBuffer = it }).append(text)
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
        explicitReasoningBuffer = null
        rawContentBuffer = null
        reasoningId = null
        answerId = null
    }
}

private data class ThinkSplit(val reasoning: String, val answer: String)

private const val THINK_OPEN = "<think>"
private const val THINK_CLOSE = "</think>"

/**
 * Longest suffix of [text] that is a proper prefix of [tag] — e.g. text ending in "<thi" holds
 * back 4 chars of a possible "<think>", ending in "<think" holds back 6, no overlap holds back 0.
 * Used to avoid leaking a tag fragment as visible text just because it happened to land at a
 * chunk boundary before the rest of the tag arrived in a later token.
 */
private fun partialTagPrefixLenAtEnd(text: String, tag: String): Int {
    for (len in minOf(text.length, tag.length - 1) downTo 1) {
        if (text.endsWith(tag.substring(0, len))) return len
    }
    return 0
}

/**
 * Splits <think>...</think> spans out of [raw] into [ThinkSplit.reasoning], leaving everything
 * else as [ThinkSplit.answer]. An unclosed trailing <think> (still streaming in) counts as
 * reasoning-so-far rather than being held back until </think> arrives — otherwise a model's whole
 * thinking phase would show nothing at all until it finishes. Re-run on the whole accumulated
 * buffer on every call (not just the latest chunk), so a tag split across two onToken calls (e.g.
 * "<thi" then "nk>...") still parses correctly instead of leaking "<thi" as answer text for one
 * frame. answer is left-trimmed since models typically leave a blank line right after </think>
 * before the real answer starts.
 */
private fun splitThink(raw: String): ThinkSplit {
    val reasoning = StringBuilder()
    val answer = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val start = raw.indexOf(THINK_OPEN, i)
        if (start < 0) {
            val tail = raw.substring(i)
            val holdBack = partialTagPrefixLenAtEnd(tail, THINK_OPEN)
            answer.append(tail, 0, tail.length - holdBack)
            break
        }
        answer.append(raw, i, start)
        val contentStart = start + THINK_OPEN.length
        val end = raw.indexOf(THINK_CLOSE, contentStart)
        if (end < 0) {
            val tail = raw.substring(contentStart)
            val holdBack = partialTagPrefixLenAtEnd(tail, THINK_CLOSE)
            reasoning.append(tail, 0, tail.length - holdBack)
            break
        }
        reasoning.append(raw, contentStart, end)
        i = end + THINK_CLOSE.length
    }
    return ThinkSplit(reasoning.toString(), answer.toString().trimStart('\n', ' '))
}
