package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.session.EntryRole
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk

private class FixedTool(override val name: String, private val body: suspend () -> String) : Tool {
    override val description = "test"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override suspend fun execute(argumentsJson: String) = body()
}

class AgentLoopToolEventTest : FunSpec({
    val sessionManager = mockk<SessionManager> { every { save(any()) } just Runs }
    val config = AgentConfig(model = "test-model")

    suspend fun runOneToolTurn(tool: Tool): List<TurnEvent> {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.ToolUse(listOf(ToolCall("c1", tool.name, "{}")), TokenUsage(1, 1)),
            LLMResponse.Text("done", TokenUsage(1, 1))
        )
        val loop = AgentLoop(provider, ToolRegistry().register(tool), sessionManager)
        val events = mutableListOf<TurnEvent>()
        loop.turn(AgentSession(id = "s1"), "go", config) { events.add(it) }
        return events
    }

    test("successful tool call emits ToolCallFinished with isError=false and a duration") {
        val finished = runOneToolTurn(FixedTool("ok") { "fine" })
            .filterIsInstance<TurnEvent.ToolCallFinished>().single()
        finished.isError.shouldBeFalse()
        finished.durationMillis shouldBeGreaterThanOrEqual 0L
    }

    test("throwing tool emits ToolCallFinished with isError=true") {
        val finished = runOneToolTurn(FixedTool("boom") { error("nope") })
            .filterIsInstance<TurnEvent.ToolCallFinished>().single()
        finished.isError.shouldBeTrue()
    }

    test("tool rounds are persisted as replay=false entries in order") {
        // reuse runOneToolTurn's provider scripting but keep the session:
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.ToolUse(listOf(ToolCall("c1", "ok", "{}")), TokenUsage(1, 1)),
            LLMResponse.Text("done", TokenUsage(1, 1))
        )
        val loop = AgentLoop(provider, ToolRegistry().register(FixedTool("ok") { "fine" }), sessionManager)
        val session = loop.turn(AgentSession(id = "s2"), "go", config)

        val roles = session.branch().map { it.role }
        roles shouldBe listOf(EntryRole.USER, EntryRole.ASSISTANT, EntryRole.TOOL_RESULT, EntryRole.ASSISTANT)
        val toolCallEntry = session.branch()[1]
        toolCallEntry.metadata["replay"] shouldBe "false"
        toolCallEntry.metadata["toolCalls"]!!.contains("\"name\":\"ok\"") shouldBe true
        val resultEntry = session.branch()[2]
        resultEntry.content shouldBe "fine"
        resultEntry.metadata["toolCallId"] shouldBe "c1"
        resultEntry.metadata["toolName"] shouldBe "ok"
    }
})
