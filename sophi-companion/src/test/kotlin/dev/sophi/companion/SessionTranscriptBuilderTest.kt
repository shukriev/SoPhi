package dev.sophi.companion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SessionTranscriptBuilderTest : FunSpec({
    test("startTurn appends a 'you:' line") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hello there")
        builder.transcript.value shouldBe listOf("you: hello there")
    }

    test("consecutive onToken calls accumulate into a single replacing 'sophi:' line, not one line per token") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hi")
        builder.onToken("Hel")
        builder.onToken("lo")
        builder.onToken("!")
        builder.transcript.value shouldBe listOf("you: hi", "sophi: Hello!")
    }

    test("onReasoningToken accumulates into its own 'sophi (thinking):' line, separate from the answer line") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hi")
        builder.onReasoningToken("thinking")
        builder.onReasoningToken("...")
        builder.onToken("answer")
        builder.transcript.value shouldBe listOf("you: hi", "sophi (thinking): thinking...", "sophi: answer")
    }

    test("onToolCallStarted/onToolCallFinished append discrete lines and end the current answer segment") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hi")
        builder.onToken("before")
        builder.onToolCallStarted("read_file", """{"path":"a.txt"}""")
        builder.onToolCallFinished("read_file", "contents", isError = false)
        builder.onToken("after")

        builder.transcript.value shouldBe listOf(
            "you: hi",
            "sophi: before",
            """sophi (tool): read_file({"path":"a.txt"})""",
            "sophi (tool result): read_file -> contents",
            "sophi: after"
        )
    }

    test("a failed tool call is prefixed ERROR:") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("hi")
        builder.onToolCallStarted("bash", "{}")
        builder.onToolCallFinished("bash", "boom", isError = true)
        builder.transcript.value.last() shouldBe "sophi (tool result): bash -> ERROR: boom"
    }

    test("startTurn for a new turn starts a fresh answer line instead of continuing the previous turn's") {
        val builder = SessionTranscriptBuilder()
        builder.startTurn("first")
        builder.onToken("one")
        builder.endTurn()
        builder.startTurn("second")
        builder.onToken("two")

        builder.transcript.value shouldBe listOf("you: first", "sophi: one", "you: second", "sophi: two")
    }

    test("a new transcript starts empty") {
        SessionTranscriptBuilder().transcript.value shouldBe emptyList()
    }
})
