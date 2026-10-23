package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class ToolSearchToolTest : FunSpec({

    fun fakeTool(name: String, description: String, deferred: Boolean) = object : Tool {
        override val name = name
        override val description = description
        override val parametersJson = "{}"
        override val deferred = deferred
        override suspend fun execute(argumentsJson: String) = ""
    }

    val registry = ToolRegistry()
        .register(fakeTool("gh__create_issue", "Create a GitHub issue", true))
        .register(fakeTool("gh__list_prs", "List pull requests", true))
        .register(fakeTool("cal__add_event", "Add a calendar event", true))
        .register(fakeTool("read_file", "Read a file", false))
    val search = ToolSearchTool(registry)
    val noMatch = { q: String ->
        "No deferred tool matches \"$q\". Deferred tools: cal__add_event, gh__create_issue, gh__list_prs"
    }

    test("description lists the deferred tool names") {
        search.description shouldContain "cal__add_event, gh__create_issue, gh__list_prs"
    }

    test("a query activates the matching tools in the current turn") {
        withContext(ActivatedTools()) {
            val out = search.execute("""{"query":"github issue"}""")
            out shouldStartWith "Found 1 tool. It is now callable:"
            out shouldContain "- gh__create_issue: Create a GitHub issue"
            coroutineContext[ActivatedTools]!!.names shouldBe setOf("gh__create_issue")
        }
    }

    test("matches rank by how many query words hit") {
        val out = search.execute("""{"query":"list github pull requests"}""")
        out.lines().first { it.startsWith("- ") } shouldStartWith "- gh__list_prs"
    }

    test("one-letter query words are ignored") {
        val out = search.execute("""{"query":"create a github issue"}""")
        out shouldStartWith "Found 1 tool. It is now callable:"
        out shouldContain "gh__create_issue"
    }

    test("direct tools are never returned") {
        search.execute("""{"query":"read file"}""") shouldBe noMatch("read file")
    }

    test("max_results caps the matches") {
        val out = search.execute("""{"query":"gh","max_results":1}""")
        out.lines().count { it.startsWith("- ") } shouldBe 1
    }

    test("no match lists what is deferred") {
        search.execute("""{"query":"weather"}""") shouldBe noMatch("weather")
    }

    test("a blank query answers instead of throwing") {
        search.execute("""{"query":"  "}""") shouldBe noMatch("")
        search.execute("{}") shouldBe noMatch("")
    }

    test("activations stay inside their own context") {
        val a = ActivatedTools()
        val b = ActivatedTools()
        withContext(a) { search.execute("""{"query":"github issue"}""") }
        withContext(b) { search.execute("""{"query":"calendar"}""") }
        a.names shouldBe setOf("gh__create_issue")
        b.names shouldBe setOf("cal__add_event")
    }

    test("outside a turn it still answers") {
        search.execute("""{"query":"calendar"}""") shouldStartWith "Found 1 tool."
    }
})
