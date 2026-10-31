package dev.sophi.core.tools

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking

private fun items(vararg pairs: Pair<String, String>) =
    pairs.joinToString(",", """{"items":[""", "]}") { (t, s) -> """{"text":"$t","status":"$s"}""" }

/** A session whose branch holds `todo` results with [contents], as the loop persists them. */
internal fun sessionWithTodoResults(vararg contents: String) = AgentSession("s").apply {
    append(EntryRole.USER, "go")
    contents.forEach { append(EntryRole.TOOL_RESULT, it, mapOf("replay" to "false", "toolCallId" to "c", "toolName" to "todo")) }
}

class TodoToolTest : FunSpec({
    val tool = TodoTool()
    fun run(json: String) = runBlocking { tool.execute(json) }

    test("renders and validates") {
        run(items("a" to "in_progress", "b" to "pending")) shouldBe "[>] a\n[ ] b"
        run(items(*Array(31) { "t$it" to "pending" })) shouldBe "Error: at most 30 items"
        run(items("x".repeat(201) to "pending")) shouldStartWith "Error:"
        run(items("x".repeat(201) to "pending")) shouldContain "200"
        run(items("a" to "in_progress", "b" to "in_progress")) shouldBe "Error: only one item can be in_progress"
        run(items("a" to "maybe")) shouldStartWith "Error:"
        tool.riskLevel("{}") shouldBe RiskLevel.SAFE
    }

    test("empty list clears") {
        val cleared = run("""{"items":[]}""")
        cleared shouldBe "(todo list cleared)"
        currentTodos(sessionWithTodoResults("[ ] a", cleared)) shouldBe emptyList()
    }

    test("currentTodos reads the newest result on the branch") {
        val s = sessionWithTodoResults(run(items("a" to "pending")), run(items("a" to "done", "b" to "in_progress")), "Error: at most 30 items")
        currentTodos(s) shouldBe listOf(TodoItem("a", "done"), TodoItem("b", "in_progress"))
    }

    test("an unreadable newest result falls back to an older one") {
        currentTodos(sessionWithTodoResults("[ ] a", "Stopped by the user before this finished.")) shouldBe listOf(TodoItem("a", "pending"))
    }

    test("the loop's repeated-call note after the list is ignored") {
        val noted = "[x] a\n[ ] b\n\n[Note: you have now made this exact call 3 times.]"
        currentTodos(sessionWithTodoResults(noted)) shouldBe listOf(TodoItem("a", "done"), TodoItem("b", "pending"))
    }

    test("no todo call is null") {
        currentTodos(AgentSession("s").apply { append(EntryRole.USER, "hi") }) shouldBe null
    }
})
