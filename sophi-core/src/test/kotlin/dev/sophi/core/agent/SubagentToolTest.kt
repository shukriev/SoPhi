package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class SubagentToolTest : FunSpec({

    val explore = AgentDefinition(
        name = "explore",
        description = "Read-only search",
        systemPrompt = "You search code.",
        allowedTools = listOf("read_file")
    )
    val recursive = AgentDefinition(
        name = "recursive",
        description = "Can delegate further",
        systemPrompt = "You can delegate.",
        allowedTools = listOf("delegate_to_subagent")
    )

    val readTool = object : Tool {
        override val name = "read_file"
        override val description = "Reads a file"
        override val parametersJson = "{}"
        override suspend fun execute(argumentsJson: String) = "file contents"
    }

    fun writeTool() = object : Tool {
        override val name = "write_file"
        override val description = "Writes a file"
        override val parametersJson = "{}"
        override suspend fun execute(argumentsJson: String) = "written"
    }

    fun buildTool(
        definitions: List<AgentDefinition>,
        provider: LLMProvider,
        sessionsDir: Path,
        fullRegistry: ToolRegistry = ToolRegistry().register(readTool),
        depth: Int = 0
    ): SubagentTool = SubagentTool(
        definitions = definitions,
        provider = provider,
        fullRegistry = fullRegistry,
        sessionManager = FileSessionManager(sessionsDir),
        parentSessionId = "parent-1",
        parentConfig = AgentConfig(model = "parent-model"),
        depth = depth
    )

    test("name is delegate_to_subagent") {
        buildTool(listOf(explore), mockk(), createTempDirectory("subagent-test")).name shouldBe "delegate_to_subagent"
    }

    test("description lists available agent types") {
        val tool = buildTool(listOf(explore), mockk(), createTempDirectory("subagent-test"))
        tool.description shouldContain "explore"
        tool.description shouldContain "Read-only search"
    }

    test("execute() returns error for unknown subagent type without calling the LLM") {
        val provider = mockk<LLMProvider>()
        val tool = buildTool(listOf(explore), provider, createTempDirectory("subagent-test"))

        val result = runBlocking { tool.execute("""{"subagent_type":"ghost","prompt":"do something"}""") }

        result shouldContain "unknown subagent type"
        coVerify(exactly = 0) { provider.complete(any()) }
    }

    test("execute() returns error when max delegation depth is exceeded, without calling the LLM") {
        val provider = mockk<LLMProvider>()
        val tool = buildTool(listOf(explore), provider, createTempDirectory("subagent-test"), depth = 3)

        val result = runBlocking { tool.execute("""{"subagent_type":"explore","prompt":"go"}""") }

        result shouldContain "max delegation depth"
        coVerify(exactly = 0) { provider.complete(any()) }
    }

    test("execute() runs the nested loop and returns its final text") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("Found it in Auth.kt", TokenUsage(10, 5))
        val tool = buildTool(listOf(explore), provider, createTempDirectory("subagent-test"))

        val result = runBlocking { tool.execute("""{"subagent_type":"explore","prompt":"find auth code"}""") }

        result shouldBe "Found it in Auth.kt"
    }

    test("execute() persists the subagent session tagged with the parent session id") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("done", TokenUsage(1, 1))
        val sessionsDir = createTempDirectory("subagent-test")
        val sessionManager = FileSessionManager(sessionsDir)
        val tool = SubagentTool(
            definitions = listOf(explore),
            provider = provider,
            fullRegistry = ToolRegistry().register(readTool),
            sessionManager = sessionManager,
            parentSessionId = "parent-42",
            parentConfig = AgentConfig(model = "parent-model")
        )

        runBlocking { tool.execute("""{"subagent_type":"explore","prompt":"find auth code"}""") }

        val metas = sessionManager.list()
        metas shouldHaveSize 1
        metas.first().parentSessionId shouldBe "parent-42"
    }

    test("execute() scopes the nested loop to only the definition's allowedTools") {
        val provider = mockk<LLMProvider>()
        val capturedRequests = mutableListOf<CompletionRequest>()
        coEvery { provider.complete(any()) } answers {
            capturedRequests.add(firstArg())
            LLMResponse.Text("ok", TokenUsage(1, 1))
        }
        val fullRegistry = ToolRegistry().register(readTool).register(writeTool())
        val tool = buildTool(listOf(explore), provider, createTempDirectory("subagent-test"), fullRegistry = fullRegistry)

        runBlocking { tool.execute("""{"subagent_type":"explore","prompt":"go"}""") }

        capturedRequests.first().tools.map { it.name } shouldBe listOf("read_file")
    }

    test("execute() allows further delegation only when allowedTools includes the delegate tool itself") {
        val provider = mockk<LLMProvider>()
        val capturedRequests = mutableListOf<CompletionRequest>()
        coEvery { provider.complete(any()) } answers {
            capturedRequests.add(firstArg())
            LLMResponse.Text("ok", TokenUsage(1, 1))
        }
        val tool = buildTool(listOf(recursive), provider, createTempDirectory("subagent-test"))

        runBlocking { tool.execute("""{"subagent_type":"recursive","prompt":"go"}""") }

        capturedRequests.first().tools.map { it.name } shouldBe listOf("delegate_to_subagent")
    }
})
