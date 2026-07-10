package dev.sophi.learning

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan

class TrajectoryRendererTest : FunSpec({
    fun entry(role: EntryRole, content: String, meta: Map<String, String> = emptyMap()) =
        SessionEntry("id-$content".take(20), null, role, content, 1L, meta)

    test("renders roles, tool calls and truncated tool results") {
        val text = TrajectoryRenderer.render(listOf(
            entry(EntryRole.USER, "fix the build"),
            entry(EntryRole.ASSISTANT, "", mapOf("replay" to "false",
                "toolCalls" to """[{"id":"c1","name":"bash","argumentsJson":"{\"cmd\":\"mvn test\"}"}]""")),
            entry(EntryRole.TOOL_RESULT, "x".repeat(500), mapOf("replay" to "false", "toolName" to "bash")),
            entry(EntryRole.ASSISTANT, "fixed")
        ), budgetTokens = 8000)
        text shouldContain "USER: fix the build"
        text shouldContain "[tool call] bash"
        text shouldContain "ASSISTANT: fixed"
        text shouldNotContain "x".repeat(400)   // tool result truncated to 300 chars
    }

    test("over budget: head and tail kept, middle elided") {
        val entries = (1..200).map { entry(EntryRole.USER, "message number $it padded ${"y".repeat(80)}") }
        val text = TrajectoryRenderer.render(entries, budgetTokens = 500)
        text shouldContain "message number 1 "
        text shouldContain "message number 200"
        text shouldContain "entries elided"
        text.length shouldBeLessThan 500 * 4 + 500
    }
})
