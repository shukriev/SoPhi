package dev.sophi.sdk

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.SophiPlugin
import dev.sophi.mcp.McpClientManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.io.path.createTempDirectory

class SophiRuntimeTest : FunSpec({
    val agentLoop = mockk<AgentLoop>()
    val sessionManager = mockk<SessionManager>()
    val config = AgentConfig(model = "test-model")
    lateinit var runtime: SophiRuntime

    beforeTest {
        clearMocks(agentLoop, sessionManager)
        runtime = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config)
    }

    test("newSession returns session id") {
        every { sessionManager.create(null) } returns AgentSession("sess-1")
        every { sessionManager.save(any()) } just runs
        runtime.newSession() shouldBe "sess-1"
    }

    test("newSession passes title to sessionManager") {
        every { sessionManager.create("My Chat") } returns AgentSession("sess-2", "My Chat")
        every { sessionManager.save(any()) } just runs
        runtime.newSession("My Chat") shouldBe "sess-2"
    }

    test("turn returns last assistant reply") {
        val session = AgentSession("s1")
        val updated = AgentSession(
            "s1", initialEntries = listOf(
                SessionEntry("e1", null, EntryRole.USER, "hi", 0L),
                SessionEntry("e2", "e1", EntryRole.ASSISTANT, "hello!", 0L)
            )
        )
        every { sessionManager.load("s1") } returns session
        coEvery { agentLoop.turn(session, "hi", config, any()) } returns updated
        runtime.turn("s1", "hi") shouldBe "hello!"
    }

    test("turn propagates exception when session not found") {
        every { sessionManager.load("bad") } throws IllegalArgumentException("not found")
        shouldThrow<IllegalArgumentException> { runtime.turn("bad", "hi") }
    }

    test("turn dispatches ON_ERROR hook when agentLoop throws") {
        val log = mutableListOf<HookPoint>()
        val errorPlugin = object : SophiPlugin {
            override val name = "error-spy"
            override fun hooks() = listOf(object : AgentHook {
                override val point = HookPoint.ON_ERROR
                override suspend fun invoke(context: HookContext) { log.add(HookPoint.ON_ERROR) }
            })
        }
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry().register(errorPlugin), config)
        val session = AgentSession("s1")
        every { sessionManager.load("s1") } returns session
        coEvery { agentLoop.turn(session, "hi", config, any()) } throws RuntimeException("LLM error")

        shouldThrow<RuntimeException> { rt.turn("s1", "hi") }
        log shouldBe listOf(HookPoint.ON_ERROR)
    }

    test("RuntimeBuilder build throws when no provider set") {
        shouldThrow<IllegalArgumentException> { RuntimeBuilder().build() }
    }

    test("RuntimeBuilder wires confirmationPolicy through to the built AgentLoop, denying a DESTRUCTIVE tool") {
        val provider = mockk<LLMProvider>()
        val destructiveTool = object : Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override val riskLevel = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "should not run"
        }
        val capturedRequests = mutableListOf<CompletionRequest>()
        coEvery { provider.complete(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(calls = listOf(ToolCall("c1", "danger", "{}")), usage = TokenUsage(1, 0))
            else
                LLMResponse.Text("done", TokenUsage(1, 1))
        }

        val builder = RuntimeBuilder()
        builder.provider = provider
        builder.sessionsDir = createTempDirectory("sophi-sdk-test")
        val rt = builder
            .tool(destructiveTool)
            .confirmationPolicy(ConfirmationPolicy { _, _ -> false })
            .build()

        val sessionId = rt.newSession()
        rt.turn(sessionId, "do it")

        capturedRequests[1].messages.last().content shouldBe
            "Error: Tool 'danger' execution denied by confirmation policy"
    }

    test("RuntimeBuilder.mcpConfig registers tools returned by McpClientManager.connect and build() closes it via SophiRuntime.close()") {
        val provider = mockk<LLMProvider>()
        val mcpManager = mockk<McpClientManager>()
        coEvery { mcpManager.connect(emptyList()) } returns emptyList()
        every { mcpManager.close() } just runs

        val rt = RuntimeBuilder()
            .also { it.provider = provider }
            .also { it.sessionsDir = createTempDirectory("sophi-sdk-test") }
            .mcpClientManager(mcpManager)
            .build()

        rt.close()

        verify { mcpManager.close() }
    }
})
