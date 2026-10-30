package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.context.CompactionMode
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.TodoTool
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk

private const val TODO_ARGS = """{"items":[{"text":"write the parser","status":"in_progress"},{"text":"test it","status":"pending"}]}"""
private val LONG_TODO = (listOf("""{"text":"write the parser","status":"in_progress"}""") +
    (1..9).map { """{"text":"step $it: a realistic step description that runs to about sixty chars","status":"pending"}""" })
    .joinToString(",", """{"items":[""", "]}")
private fun todoCall(id: String, input: Int, args: String = TODO_ARGS) = LLMResponse.ToolUse(listOf(ToolCall(id, "todo", args)), TokenUsage(input, 0))
private fun bigCall(id: String, input: Int) = LLMResponse.ToolUse(listOf(ToolCall(id, "big", "{}")), TokenUsage(input, 0))
private val big = object : Tool {
    override val name = "big"; override val description = "b"; override val parametersJson = "{}"
    override suspend fun execute(argumentsJson: String) = List(200) { "output line $it" }.joinToString("\n")
}

class AgentLoopTodoTest : FunSpec({
    val config = AgentConfig(model = "m", maxToolRounds = 20)

    fun run(rounds: List<LLMResponse>, mode: CompactionMode = CompactionMode.GENERIC, session: AgentSession = AgentSession("s"),
            captured: MutableList<CompletionRequest> = mutableListOf(), input: String = "go"): AgentSession {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        every { provider.stream(any()) } answers {
            captured.add(firstArg())
            rounds[(captured.size - 1).coerceAtMost(rounds.size - 1)].toStreamFlow()
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("the summary", TokenUsage(1, 1))
        every { sm.save(any()) } just Runs
        val loop = AgentLoop(provider, ToolRegistry().register(TodoTool()).register(big), sm,
            loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE, contextWindowTokens = 100_000, compaction = mode)
        return kotlinx.coroutines.runBlocking { loop.turn(session, input, config) }
    }

    test("a turn after a todo turn sends the list, without saving it into the session") {
        val after = run(listOf(todoCall("t1", 10), LLMResponse.Text("ok", TokenUsage(10, 1))))
        val captured = mutableListOf<CompletionRequest>()
        val settled = run(listOf(LLMResponse.Text("next", TokenUsage(10, 1))), session = after, captured = captured, input = "continue")
        val lastUser = captured.single().messages.last { it.role == MessageRole.USER }.content
        lastUser shouldContain "continue"
        lastUser shouldContain "[>] write the parser"
        lastUser shouldContain "[ ] test it"
        settled.branch().filter { it.role == EntryRole.USER }.last().content shouldBe "continue"
    }

    test("in-turn compaction keeps the todo list in its summary") {
        val captured = mutableListOf<CompletionRequest>()
        run(listOf(todoCall("t1", 1_000), bigCall("b2", 1_000), bigCall("b3", 90_000), LLMResponse.Text("done", TokenUsage(1_000, 5))), captured = captured)
        val user = captured[3].messages.first { it.role == MessageRole.USER }.content
        user shouldContain "the summary"
        user shouldContain "Current todo list:\n[>] write the parser"
    }

    test("elision never stubs a todo result") {
        val captured = mutableListOf<CompletionRequest>()
        run(listOf(todoCall("t1", 1_000, LONG_TODO), bigCall("b2", 1_000), bigCall("b3", 1_000), bigCall("b4", 79_800), LLMResponse.Text("done", TokenUsage(1_000, 5))),
            mode = CompactionMode.STRUCTURED, captured = captured)
        val msgs = captured.last().messages
        msgs.any { it.role == MessageRole.TOOL && it.content.contains("[elided") } shouldBe true
        val todo = msgs.single { it.role == MessageRole.TOOL && it.toolName == "todo" }
        todo.content shouldContain "[>] write the parser"
    }
})
