package dev.sophi.companion

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SessionTranscriptBuilderTest : FunSpec({
    test("startTurn appends a UserMessage entry") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hello there")
        builder.transcript.value shouldBe listOf(TranscriptEntry.UserMessage(0, "hello there"))
    }

    test("consecutive onToken calls accumulate into a single Answer entry at the same id, not one entry per token") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hi")
        builder.onToken("Hel")
        builder.onToken("lo")
        builder.onToken("!")
        builder.transcript.value shouldBe listOf(
            TranscriptEntry.UserMessage(0, "hi"),
            TranscriptEntry.Answer(1, "Hello!")
        )
    }

    test("onReasoningToken accumulates into its own Reasoning entry, separate from the answer entry") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hi")
        builder.onReasoningToken("thinking")
        builder.onReasoningToken("...")
        builder.onToken("answer")
        builder.transcript.value shouldBe listOf(
            TranscriptEntry.UserMessage(0, "hi"),
            TranscriptEntry.Reasoning(1, "thinking..."),
            TranscriptEntry.Answer(2, "answer")
        )
    }

    test("onToolCallStarted/onToolCallFinished merge into one ToolInvocation entry and end the current answer segment") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hi")
        builder.onToken("before")
        builder.onToolCallStarted("read_file", """{"path":"a.txt"}""")
        builder.onToolCallFinished("read_file", "contents", isError = false)
        builder.onToken("after")

        builder.transcript.value shouldBe listOf(
            TranscriptEntry.UserMessage(0, "hi"),
            TranscriptEntry.Answer(1, "before"),
            TranscriptEntry.ToolInvocation(2, "read_file", """{"path":"a.txt"}""", "contents", false),
            TranscriptEntry.Answer(3, "after")
        )
    }

    test("a failed tool call sets isError true") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hi")
        builder.onToolCallStarted("bash", "{}")
        builder.onToolCallFinished("bash", "boom", isError = true)
        builder.transcript.value.last() shouldBe TranscriptEntry.ToolInvocation(1, "bash", "{}", "boom", true)
    }

    test("two concurrent same-name tool calls resolve their finish events in start order (FIFO)") {
        val builder = SessionTranscriptBuilder()
        builder.onToolCallStarted("bash", "{\"cmd\":\"first\"}")
        builder.onToolCallStarted("bash", "{\"cmd\":\"second\"}")
        builder.onToolCallFinished("bash", "first-result", isError = false)
        builder.onToolCallFinished("bash", "second-result", isError = false)

        builder.transcript.value shouldBe listOf(
            TranscriptEntry.ToolInvocation(0, "bash", "{\"cmd\":\"first\"}", "first-result", false),
            TranscriptEntry.ToolInvocation(1, "bash", "{\"cmd\":\"second\"}", "second-result", false)
        )
    }

    test("two concurrent different-name tool calls resolve independently regardless of finish order") {
        val builder = SessionTranscriptBuilder()
        builder.onToolCallStarted("read_file", "{}")
        builder.onToolCallStarted("bash", "{}")
        builder.onToolCallFinished("bash", "bash-done", isError = false)
        builder.onToolCallFinished("read_file", "file-contents", isError = false)

        builder.transcript.value shouldBe listOf(
            TranscriptEntry.ToolInvocation(0, "read_file", "{}", "file-contents", false),
            TranscriptEntry.ToolInvocation(1, "bash", "{}", "bash-done", false)
        )
    }

    test("an unmatched ToolCallFinished event is ignored, not thrown") {
        val builder = SessionTranscriptBuilder()
        builder.onToolCallFinished("bash", "result", isError = false)
        builder.transcript.value shouldBe emptyList()
    }

    test("startTurn for a new turn starts a fresh Answer entry instead of continuing the previous turn's") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("first")
        builder.onToken("one")
        builder.endTurn()
        builder.startTurn("second")
        builder.onToken("two")

        builder.transcript.value shouldBe listOf(
            TranscriptEntry.UserMessage(0, "first"),
            TranscriptEntry.Answer(1, "one"),
            TranscriptEntry.UserMessage(2, "second"),
            TranscriptEntry.Answer(3, "two")
        )
    }

    test("a new transcript starts empty") {
        SessionTranscriptBuilder().transcript.value shouldBe emptyList()
    }

    test("seed prepends persisted entries when the transcript is empty") {
        val builder = SessionTranscriptBuilder()
        val seeded = listOf(TranscriptEntry.UserMessage(0, "past turn"), TranscriptEntry.Answer(1, "past reply"))
        builder.seed(seeded)
        builder.transcript.value shouldBe seeded
    }

    test("seed is a no-op once the transcript is non-empty, so it never clobbers live turns") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("live")
        builder.seed(listOf(TranscriptEntry.UserMessage(99, "should not appear")))
        builder.transcript.value shouldBe listOf(TranscriptEntry.UserMessage(0, "live"))
    }

    test("seed advances the id counter past the seeded entries, so live turns after a reload get fresh ids") {
        val builder = SessionTranscriptBuilder()
        builder.seed(listOf(TranscriptEntry.UserMessage(5, "past"), TranscriptEntry.Answer(6, "reply")))
        builder.startTurn("new turn")
        builder.transcript.value.last() shouldBe TranscriptEntry.UserMessage(7, "new turn")
    }

    test("entriesFor maps a USER entry to UserMessage") {
        val entries = listOf(SessionEntry(id = "e1", role = EntryRole.USER, content = "hi", timestamp = 1L))
        SessionTranscriptBuilder.entriesFor(entries) shouldBe listOf(TranscriptEntry.UserMessage(0, "hi"))
    }

    test("entriesFor maps a plain ASSISTANT entry (no toolCalls metadata) to Answer") {
        val entries = listOf(SessionEntry(id = "e1", role = EntryRole.ASSISTANT, content = "the answer", timestamp = 1L))
        SessionTranscriptBuilder.entriesFor(entries) shouldBe listOf(TranscriptEntry.Answer(0, "the answer"))
    }

    test("entriesFor matches ToolInvocation to its TOOL_RESULT by toolCallId, not by arrival order") {
        val toolCallsJson = """[{"id":"call-2","name":"bash","argumentsJson":"{}"},""" +
            """{"id":"call-1","name":"read_file","argumentsJson":"{\"path\":\"a.txt\"}"}]"""
        val entries = listOf(
            SessionEntry(id = "e1", role = EntryRole.USER, content = "do stuff", timestamp = 1L),
            SessionEntry(
                id = "e2", role = EntryRole.ASSISTANT, content = "", timestamp = 2L,
                metadata = mapOf("toolCalls" to toolCallsJson)
            ),
            // TOOL_RESULT entries arrive with call-1's result written before call-2's — the
            // reverse of the calls' own emission order — proving the match is by toolCallId,
            // not by position.
            SessionEntry(
                id = "e3", role = EntryRole.TOOL_RESULT, content = "file-contents", timestamp = 3L,
                metadata = mapOf("toolCallId" to "call-1", "toolName" to "read_file")
            ),
            SessionEntry(
                id = "e4", role = EntryRole.TOOL_RESULT, content = "bash-output", timestamp = 4L,
                metadata = mapOf("toolCallId" to "call-2", "toolName" to "bash")
            ),
            SessionEntry(id = "e5", role = EntryRole.ASSISTANT, content = "done", timestamp = 5L),
        )

        SessionTranscriptBuilder.entriesFor(entries) shouldBe listOf(
            TranscriptEntry.UserMessage(0, "do stuff"),
            TranscriptEntry.ToolInvocation(1, "bash", "{}", "bash-output", false),
            TranscriptEntry.ToolInvocation(2, "read_file", "{\"path\":\"a.txt\"}", "file-contents", false),
            TranscriptEntry.Answer(3, "done"),
        )
    }

    test("entriesFor infers isError from a TOOL_RESULT's Error:-prefixed content") {
        val toolCallsJson = """[{"id":"call-1","name":"bash","argumentsJson":"{}"}]"""
        val entries = listOf(
            SessionEntry(
                id = "e1", role = EntryRole.ASSISTANT, content = "", timestamp = 1L,
                metadata = mapOf("toolCalls" to toolCallsJson)
            ),
            SessionEntry(
                id = "e2", role = EntryRole.TOOL_RESULT, content = "Error: boom", timestamp = 2L,
                metadata = mapOf("toolCallId" to "call-1", "toolName" to "bash")
            ),
        )
        SessionTranscriptBuilder.entriesFor(entries) shouldBe listOf(
            TranscriptEntry.ToolInvocation(0, "bash", "{}", "Error: boom", true)
        )
    }

    test("entriesFor skips SYSTEM entries") {
        val entries = listOf(
            SessionEntry(id = "e1", role = EntryRole.SYSTEM, content = "compacted summary", timestamp = 1L),
            SessionEntry(id = "e2", role = EntryRole.USER, content = "hi", timestamp = 2L),
        )
        SessionTranscriptBuilder.entriesFor(entries) shouldBe listOf(TranscriptEntry.UserMessage(0, "hi"))
    }
})
