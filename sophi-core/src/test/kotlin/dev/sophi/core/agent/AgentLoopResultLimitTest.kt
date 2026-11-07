package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ResultReadTool
import dev.sophi.core.tools.ResultStore
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

private class BigOutputTool(private val output: String) : Tool {
    override val name = "out"
    override val description = "returns text"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override suspend fun execute(argumentsJson: String) = output
}

/** One tool round per scripted call, then "done". */
private class ScriptedCallsProvider(private val calls: List<ToolCall>) : LLMProvider {
    private var n = 0
    override val name = "scripted"
    override suspend fun complete(request: CompletionRequest): LLMResponse = error("not used")
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        if (n < calls.size) emit(StreamEvent.ToolCallsReady(listOf(calls[n++]))) else emit(StreamEvent.Content("done"))
    }
}

class AgentLoopResultLimitTest : FunSpec({
    val config = AgentConfig(model = "m")
    fun lines(n: Int) = (1..n).joinToString("\n") { "line %05d".format(it) }

    suspend fun run(output: String, calls: List<ToolCall>, store: ResultStore?, window: Int = 100_000, audit: MutableList<AuditEntry>? = null): Pair<AgentSession, List<String>> {
        val registry = ToolRegistry().register(BigOutputTool(output))
        store?.let { registry.register(ResultReadTool(it)) }
        val finished = mutableListOf<String>()
        val session = AgentSession(id = "s")
        val loop = AgentLoop(ScriptedCallsProvider(calls), registry, mockk<SessionManager>(relaxed = true), contextWindowTokens = window,
            audit = audit?.let { list -> AuditSink { list += it } })
        withContext(SessionIdContext("root")) {
            loop.streamTurn(session, "go", config) { if (it is TurnEvent.ToolCallFinished) finished += it.result }
        }
        return session to finished
    }
    fun results(s: AgentSession) = s.branch().filter { it.role == EntryRole.TOOL_RESULT }.map { it.content }

    test("a result that fits a page reaches the model byte for byte") {
        val text = "a\r\nb\u0000 " + "x".repeat(7_990)
        val (session, _) = run(text, listOf(ToolCall("c1", "out", "{}")), ResultStore(tempdir().toPath()))
        results(session) shouldBe listOf(text)
    }

    test("an over-page result shows a page with its id; the audit and events get it whole, and the id") {
        val text = lines(2_000) // 20k chars
        val audit = mutableListOf<AuditEntry>()
        val (session, finished) = run(text, listOf(ToolCall("c1", "out", "{}")), ResultStore(tempdir().toPath()), audit = audit)
        val shown = results(session).single()
        shown shouldContain """result_read("r1", 2)"""
        (shown.length < 8_300) shouldBe true
        finished.single() shouldBe text
        audit.single().resultId shouldBe "r1"
        audit.single().v shouldBe 2
    }

    test("result_read's page is never cut again, and comes from the root session's store") {
        val store = ResultStore(tempdir().toPath())
        val (session, _) = run(lines(2_000), listOf(ToolCall("c1", "out", "{}"), ToolCall("c2", "result_read", """{"id":"r1","page":1}""")), store)
        val page = results(session)[1]
        page shouldStartWith "[r1 page 1/3]\n"
        page.substringAfter("]\n") shouldBe lines(2_000).take(page.length - "[r1 page 1/3]\n".length)
    }

    test("the page shrinks to 20% of a small window") {
        val (session, _) = run(lines(2_000), listOf(ToolCall("c1", "out", "{}")), ResultStore(tempdir().toPath()), window = 3_000)
        // 3000 tokens * 0.2 * 4 chars = 2400: head 1600, tail 800
        val shown = results(session).single()
        shown shouldStartWith lines(2_000).take(1_600)
        shown.endsWith(lines(2_000).takeLast(800)) shouldBe true
        shown shouldContain "for the next page of 10]"
    }

    test("a glob past its old 200-file cut loses nothing: every file is on some page") {
        val root = tempdir().toPath()
        (1..1_500).forEach { root.resolve("file$it.txt").toFile().writeText("x") }
        val store = ResultStore(tempdir().toPath())
        val registry = ToolRegistry().register(dev.sophi.core.tools.GlobTool(root)).register(ResultReadTool(store))
        val session = AgentSession(id = "s")
        val reads = (1..4).map { ToolCall("p$it", "result_read", """{"id":"r1","page":$it}""") }
        withContext(SessionIdContext("root")) {
            AgentLoop(ScriptedCallsProvider(listOf(ToolCall("g", "glob", """{"pattern":"*.txt"}""")) + reads), registry,
                mockk<SessionManager>(relaxed = true), contextWindowTokens = 100_000).streamTurn(session, "go", config) {}
        }
        val pages = results(session).drop(1).filter { it.startsWith("[r1 page") }
        pages.last() shouldStartWith "[r1 page 3/3]" // 1,500 names ≈ 18k chars; page 4 is an error
        pages.joinToString("") { it.substringAfter("]\n") }.lines().filter { it.isNotEmpty() }.toSet().size shouldBe 1_500
    }

    test("without result_read the loop still says how much was left out") {
        val (session, _) = run(lines(2_000), listOf(ToolCall("c1", "out", "{}")), store = null)
        results(session).single() shouldContain "showing the start and end; the rest was not kept"
    }
})
