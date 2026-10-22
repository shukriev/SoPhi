package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.tools.ToolSearchTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk

private fun fakeTool(toolName: String, desc: String, schema: String = "{}", isDeferred: Boolean, result: String = "ok") =
    object : Tool {
        override val name = toolName
        override val description = desc
        override val parametersJson = schema
        override val deferred = isDeferred
        override suspend fun execute(argumentsJson: String) = result
    }

class AgentLoopDeferredToolsTest : FunSpec({
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")

    fun registryWithServer(): ToolRegistry {
        val registry = ToolRegistry()
        registry.register(fakeTool("read_file", "Reads a file", isDeferred = false))
        registry.register(ToolSearchTool(registry))
        (0 until 30).forEach { i ->
            val n = "srv__tool_%02d".format(i)
            registry.register(fakeTool(n, "Does thing $i", isDeferred = true))
        }
        registry.register(fakeTool("srv__weather_forecast", "Get the weather forecast", isDeferred = true, result = "sunny"))
        return registry
    }

    fun script(captured: MutableList<CompletionRequest>, rounds: List<LLMResponse>): Pair<LLMProvider, SessionManager> {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        every { provider.stream(any()) } answers {
            captured.add(firstArg())
            rounds[(captured.size - 1).coerceAtMost(rounds.size - 1)].toStreamFlow()
        }
        every { sm.save(any()) } just Runs
        return provider to sm
    }

    fun use(id: String, name: String, args: String) =
        LLMResponse.ToolUse(listOf(ToolCall(id, name, args)), TokenUsage(1, 0))

    val weatherRounds = listOf(
        use("c1", "tool_search", """{"query":"weather forecast"}"""),
        use("c2", "srv__weather_forecast", "{}"),
        LLMResponse.Text("It is sunny.", TokenUsage(1, 1)),
    )

    test("a searched tool is sent on the next round of the same turn") {
        val captured = mutableListOf<CompletionRequest>()
        val (provider, sm) = script(captured, weatherRounds)
        val loop = AgentLoop(provider, registryWithServer(), sm, contextWindowTokens = 100_000)

        val result = loop.turn(AgentSession(id = "s"), "weather?", config)

        captured[0].tools.any { it.name == "srv__weather_forecast" } shouldBe false
        captured[1].tools.any { it.name == "srv__weather_forecast" } shouldBe true
        captured[2].messages.last().content shouldBe "sunny"
        result.branch().last().content shouldBe "It is sunny."
    }

    test("activations end with the turn") {
        val captured = mutableListOf<CompletionRequest>()
        val (provider, sm) = script(captured, weatherRounds)
        val loop = AgentLoop(provider, registryWithServer(), sm, contextWindowTokens = 100_000)
        val session = loop.turn(AgentSession(id = "s"), "weather?", config)

        loop.turn(session, "thanks", config)

        captured.size shouldBe 4
        captured[3].tools.any { it.name == "srv__weather_forecast" } shouldBe false
    }

    test("a deferred tool called by name without a search still runs") {
        val captured = mutableListOf<CompletionRequest>()
        val (provider, sm) = script(
            captured,
            listOf(use("c1", "srv__tool_07", "{}"), LLMResponse.Text("done", TokenUsage(1, 1))),
        )
        val loop = AgentLoop(provider, registryWithServer(), sm, contextWindowTokens = 100_000)

        loop.turn(AgentSession(id = "s"), "go", config)

        captured[1].messages.last().content shouldBe "ok"
    }

    test("prompt tool schemas shrink by at least 70 percent with 3 servers of 30 tools") {
        val schema = """{"type":"object","properties":{"a":{"type":"string","description":"${"x".repeat(150)}"},"b":{"type":"string","description":"${"y".repeat(150)}"}},"required":["a"]}"""
        fun build(deferred: Boolean): Int {
            val registry = ToolRegistry()
            registry.register(ToolSearchTool(registry))
            (1..3).forEach { s -> (0 until 30).forEach { t ->
                registry.register(fakeTool("s%d__t%02d".format(s, t), "d".repeat(120), schema, deferred))
            } }
            return registry.definitions().sumOf { it.name.length + it.description.length + it.parametersJson.length }
        }
        build(deferred = true) shouldBeLessThanOrEqual build(deferred = false) * 30 / 100
    }
})
