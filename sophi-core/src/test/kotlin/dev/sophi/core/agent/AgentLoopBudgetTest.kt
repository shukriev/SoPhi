package dev.sophi.core.agent

import dev.sophi.ai.api.BudgetExceededException
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private class OutputTool(private val output: String) : Tool {
    override val name = "out"
    override val description = "returns text"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE
    override suspend fun execute(argumentsJson: String) = output
}

/** Answers each request with the next scripted stream; counts requests. */
private class ScriptedProvider(private val rounds: List<() -> Flow<StreamEvent>>) : LLMProvider {
    var calls = 0
    override val name = "scripted"
    override suspend fun complete(request: CompletionRequest): LLMResponse = error("not used")
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = rounds[calls++]()
}

private fun toolRound() = { flow { emit(StreamEvent.ToolCallsReady(listOf(ToolCall("c${System.nanoTime()}", "out", "{}")))); emit(StreamEvent.Usage(TokenUsage(10, 5))) } }
private fun textRound(text: String = "done") = { flow { emit(StreamEvent.Content(text)); emit(StreamEvent.Usage(TokenUsage(20, 7))) } }

class AgentLoopBudgetTest : FunSpec({
    val config = AgentConfig(model = "m")
    fun loop(p: LLMProvider, tool: Tool = OutputTool("result one"), window: Int = 100_000) =
        AgentLoop(p, ToolRegistry().apply { register(tool) }, mockk<SessionManager>(relaxed = true), contextWindowTokens = window)

    test("a refused request stops the turn as BudgetExceeded and keeps round 1") {
        val p = ScriptedProvider(listOf(toolRound(), { flow { throw BudgetExceededException("[Stopped: over budget]") } }))
        val session = AgentSession(id = "s")
        val events = mutableListOf<TurnEvent>()
        loop(p).streamTurn(session, "go", config) { events += it }
        val last = session.branch().last()
        last.metadata["stopReason"] shouldBe "BudgetExceeded"
        last.content shouldBe "[Stopped: over budget]"
        session.branch().any { it.role == EntryRole.TOOL_RESULT && it.content.contains("result one") } shouldBe true
        events.filterIsInstance<TurnEvent.Token>().last().text shouldContain "over budget"
    }

    test("a budget already spent saves the stop without any round") {
        val p = ScriptedProvider(listOf({ flow { throw BudgetExceededException("[Stopped: over budget]") } }))
        val session = AgentSession(id = "s")
        loop(p).streamTurn(session, "go", config) {}
        session.branch().map { it.role } shouldBe listOf(EntryRole.USER, EntryRole.ASSISTANT)
        session.branch().last().metadata["stopReason"] shouldBe "BudgetExceeded"
    }

    test("each round reports its usage and generation time") {
        val slowText = { flow { emit(StreamEvent.Content("a")); delay(25); emit(StreamEvent.Content("b")); emit(StreamEvent.Usage(TokenUsage(20, 7))) } }
        val p = ScriptedProvider(listOf(toolRound(), slowText))
        val events = mutableListOf<TurnEvent>()
        loop(p).streamTurn(AgentSession(id = "s"), "go", config) { events += it }
        val usages = events.filterIsInstance<TurnEvent.RoundUsage>()
        usages shouldHaveSize 2
        usages.map { it.usage } shouldBe listOf(TokenUsage(10, 5), TokenUsage(20, 7))
        usages[1].generationMillis shouldBeGreaterThanOrEqual 20L
    }

    test("a window change applies from the next turn") {
        val big = "x".repeat(3_000)
        val p = ScriptedProvider(listOf(toolRound(), textRound(), toolRound(), textRound()))
        val l = loop(p, OutputTool(big))
        val session = AgentSession(id = "s")
        l.streamTurn(session, "one", config) {}
        l.contextWindowTokens = 3_000 // 3000 * 0.2 * 4 = 2400 chars per tool result
        l.streamTurn(session, "two", config) {}
        val results = session.branch().filter { it.role == EntryRole.TOOL_RESULT }.map { it.content }
        results[0].length shouldBeGreaterThanOrEqual 3_000
        results[1] shouldContain "output truncated"
        results[1].length shouldBe 2_400 + "\n... output truncated".length
    }

    test("reasoning never reaches the saved history") {
        val p = ScriptedProvider(listOf({ flow { emit(StreamEvent.Reasoning("secret plan")); emit(StreamEvent.Content("ok")) } }))
        val session = AgentSession(id = "s")
        loop(p).streamTurn(session, "go", config) {}
        session.branch().none { it.content.contains("secret plan") || it.metadata.values.any { v -> v.contains("secret plan") } } shouldBe true
    }
})
