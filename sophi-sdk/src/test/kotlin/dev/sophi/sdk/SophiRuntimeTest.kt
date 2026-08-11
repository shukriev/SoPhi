package dev.sophi.sdk

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
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
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.SophiPlugin
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.McpConnector
import dev.sophi.mcp.McpSession
import dev.sophi.mcp.RemoteToolInfo
import dev.sophi.mcp.config.McpConfig
import dev.sophi.mcp.config.McpConfigWriter
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

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
        coEvery { agentLoop.streamTurn(session, "hi", config, any()) } returns updated
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
        coEvery { agentLoop.streamTurn(session, "hi", config, any()) } throws RuntimeException("LLM error")

        shouldThrow<RuntimeException> { rt.turn("s1", "hi") }
        log shouldBe listOf(HookPoint.ON_ERROR)
    }

    test("streamTurn forwards every TurnEvent to the caller's onEvent, and turnEventBridge hooks still fire") {
        val log = mutableListOf<HookPoint>()
        val hookPlugin = object : SophiPlugin {
            override val name = "hook-spy"
            override fun hooks() = listOf(
                object : AgentHook {
                    override val point = HookPoint.BEFORE_TOOL
                    override suspend fun invoke(context: HookContext) { log.add(HookPoint.BEFORE_TOOL) }
                },
                object : AgentHook {
                    override val point = HookPoint.AFTER_TOOL
                    override suspend fun invoke(context: HookContext) { log.add(HookPoint.AFTER_TOOL) }
                }
            )
        }
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry().register(hookPlugin), config)
        val session = AgentSession("s1")
        val updated = AgentSession(
            "s1", initialEntries = listOf(
                SessionEntry("e1", null, EntryRole.USER, "hi", 0L),
                SessionEntry("e2", "e1", EntryRole.ASSISTANT, "hello!", 0L)
            )
        )
        every { sessionManager.load("s1") } returns session
        val fakeEvents = listOf(
            TurnEvent.Token("Hel"),
            TurnEvent.Token("lo!"),
            TurnEvent.ToolCallStarted("read_file", "{}"),
            TurnEvent.ToolCallFinished("read_file", "contents", isError = false)
        )
        val onEventSlot = slot<suspend (TurnEvent) -> Unit>()
        coEvery { agentLoop.streamTurn(session, "hi", config, capture(onEventSlot)) } coAnswers {
            fakeEvents.forEach { onEventSlot.captured(it) }
            updated
        }

        val received = mutableListOf<TurnEvent>()
        val reply = rt.streamTurn("s1", "hi") { event -> received.add(event) }

        reply shouldBe "hello!"
        received shouldBe fakeEvents
        log shouldBe listOf(HookPoint.BEFORE_TOOL, HookPoint.AFTER_TOOL)
    }

    test("RuntimeBuilder build throws when no provider set") {
        shouldThrow<IllegalArgumentException> { RuntimeBuilder().build() }
    }

    test("RuntimeBuilder build throws when no context window set") {
        val builder = RuntimeBuilder()
        builder.provider = mockk<LLMProvider>()
        builder.sessionsDir = createTempDirectory("sophi-sdk-test")

        val ex = shouldThrow<IllegalArgumentException> { builder.build() }
        ex.message!! shouldContain "contextWindowTokens"
    }

    test("RuntimeBuilder wires confirmationPolicy through to the built AgentLoop, denying a DESTRUCTIVE tool") {
        val provider = mockk<LLMProvider>()
        val destructiveTool = object : Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "should not run"
        }
        val capturedRequests = mutableListOf<CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(calls = listOf(ToolCall("c1", "danger", "{}")), usage = TokenUsage(1, 0)).toStreamFlow()
            else
                LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        }

        val builder = RuntimeBuilder()
        builder.provider = provider
        builder.sessionsDir = createTempDirectory("sophi-sdk-test")
        val rt = builder
            .tool(destructiveTool)
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .confirmationPolicy(ConfirmationPolicy { requests -> requests.associate { it.callId to false } })
            .build()

        val sessionId = rt.newSession()
        rt.turn(sessionId, "do it")

        capturedRequests[1].messages.last().content shouldBe
            "Error: Tool 'danger' execution denied by confirmation policy"
    }

    test("RuntimeBuilder.grants lets a granted tool run without consulting confirmationPolicy") {
        val provider = mockk<LLMProvider>()
        var executed = false
        val destructiveTool = object : Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String { executed = true; return "ran" }
        }
        var callCount = 0
        every { provider.stream(any()) } answers {
            callCount++
            if (callCount == 1)
                LLMResponse.ToolUse(calls = listOf(ToolCall("c1", "danger", "{}")), usage = TokenUsage(1, 0)).toStreamFlow()
            else
                LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        }

        val builder = RuntimeBuilder()
        builder.provider = provider
        builder.sessionsDir = createTempDirectory("sophi-sdk-test")
        val rt = builder
            .tool(destructiveTool)
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .grants(setOf("danger"))
            .build()

        val sessionId = rt.newSession()
        rt.turn(sessionId, "go")

        executed shouldBe true
    }

    test("RuntimeBuilder.mcpConfig registers tools returned by McpClientManager.connect and build() closes it via SophiRuntime.close()") {
        val provider = mockk<LLMProvider>()
        val mcpManager = mockk<McpClientManager>()
        coEvery { mcpManager.connect(emptyList()) } returns emptyList()
        every { mcpManager.close() } just runs

        val rt = RuntimeBuilder()
            .also { it.provider = provider }
            .also { it.sessionsDir = createTempDirectory("sophi-sdk-test") }
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .mcpClientManager(mcpManager)
            .build()

        rt.close()

        verify { mcpManager.close() }
    }

    test("connectMcpServer registers the connected server's tools and returns their names") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("read_file", "reads", "{}"))
        val connector = mockk<McpConnector>()
        coEvery { connector.connect(any()) } returns session
        val mcpManager = McpClientManager(stdioConnector = connector, httpConnector = mockk())
        val toolRegistry = ToolRegistry()
        val rt = SophiRuntime(
            agentLoop, sessionManager, PluginRegistry(), config,
            mcpClientManager = mcpManager, toolRegistry = toolRegistry
        )

        val names = rt.connectMcpServer(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x")))

        names shouldBe listOf("fs__read_file")
        rt.toolNames() shouldBe listOf("fs__read_file")
    }

    test("disconnectMcpServer removes that server's tools from toolNames()") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("read_file", "reads", "{}"))
        coJustRun { session.close() }
        val connector = mockk<McpConnector>()
        coEvery { connector.connect(any()) } returns session
        val mcpManager = McpClientManager(stdioConnector = connector, httpConnector = mockk())
        val rt = SophiRuntime(
            agentLoop, sessionManager, PluginRegistry(), config,
            mcpClientManager = mcpManager, toolRegistry = ToolRegistry()
        )

        rt.connectMcpServer(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x")))
        rt.disconnectMcpServer("fs")

        rt.toolNames() shouldBe emptyList()
    }

    test("connectMcpServer throws when this runtime has no McpClientManager configured") {
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config)

        shouldThrow<IllegalArgumentException> {
            rt.connectMcpServer(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x")))
        }
    }

    test("mcpServers reads what's actually on disk at mcpConfigPath") {
        val dir = createTempDirectory("sophi-sdk-mcp-test")
        val path = dir.resolve("mcp.json")
        McpConfigWriter().write(path, McpConfig(servers = listOf(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x")))))
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config, mcpConfigPath = path)

        rt.mcpServers().map { it.name } shouldBe listOf("fs")
    }

    test("mcpServers throws a clear message when mcpConfigPath was never set") {
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config)

        val ex = shouldThrow<IllegalArgumentException> { rt.mcpServers() }
        ex.message!! shouldContain "mcpConfigPath"
    }

    test("addOrUpdateMcpServer writes a new server and connects it") {
        val dir = createTempDirectory("sophi-sdk-mcp-test")
        val path = dir.resolve("mcp.json")
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("read_file", "reads", "{}"))
        coJustRun { session.close() }
        val connector = mockk<McpConnector>()
        coEvery { connector.connect(any()) } returns session
        val mcpManager = McpClientManager(stdioConnector = connector, httpConnector = mockk())
        val rt = SophiRuntime(
            agentLoop, sessionManager, PluginRegistry(), config,
            mcpClientManager = mcpManager, mcpConfigPath = path
        )

        val newServer = McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x"), enabled = true)
        rt.addOrUpdateMcpServer(newServer)

        rt.mcpServers() shouldBe listOf(newServer)
        rt.toolNames() shouldBe listOf("fs__read_file")
    }

    test("removeMcpServer deletes the entry and disconnects it") {
        val dir = createTempDirectory("sophi-sdk-mcp-test")
        val path = dir.resolve("mcp.json")
        McpConfigWriter().write(path, McpConfig(servers = listOf(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x")))))
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("read_file", "reads", "{}"))
        coJustRun { session.close() }
        val connector = mockk<McpConnector>()
        coEvery { connector.connect(any()) } returns session
        val mcpManager = McpClientManager(stdioConnector = connector, httpConnector = mockk())
        val rt = SophiRuntime(
            agentLoop, sessionManager, PluginRegistry(), config,
            mcpClientManager = mcpManager, mcpConfigPath = path
        )
        rt.connectMcpServer(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x")))

        rt.removeMcpServer("fs")

        rt.mcpServers() shouldBe emptyList()
        rt.toolNames() shouldBe emptyList()
    }

    test("setMcpServerEnabled(false) disconnects; setMcpServerEnabled(true) reconnects") {
        val dir = createTempDirectory("sophi-sdk-mcp-test")
        val path = dir.resolve("mcp.json")
        McpConfigWriter().write(path, McpConfig(servers = listOf(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x"), enabled = true))))
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("read_file", "reads", "{}"))
        coJustRun { session.close() }
        val connector = mockk<McpConnector>()
        coEvery { connector.connect(any()) } returns session
        val mcpManager = McpClientManager(stdioConnector = connector, httpConnector = mockk())
        val rt = SophiRuntime(
            agentLoop, sessionManager, PluginRegistry(), config,
            mcpClientManager = mcpManager, mcpConfigPath = path
        )
        rt.connectMcpServer(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x")))

        rt.setMcpServerEnabled("fs", false)
        rt.mcpServers().single().enabled shouldBe false
        rt.toolNames() shouldBe emptyList()

        rt.setMcpServerEnabled("fs", true)
        rt.mcpServers().single().enabled shouldBe true
        rt.toolNames() shouldBe listOf("fs__read_file")
    }

    test("scheduleEngine builds a non-null engine when provider and contextWindowTokens are set") {
        val rt = SophiRuntime(
            agentLoop, sessionManager, PluginRegistry(), config,
            provider = mockk<LLMProvider>(), contextWindowTokens = TEST_CONTEXT_WINDOW
        )
        val dir = createTempDirectory("schedule-engine-test")

        val engine = rt.scheduleEngine(
            TaskStore(dir.resolve("tasks.json")), RunLog(dir.resolve("runs.jsonl")), NoopNotifier
        )

        engine.shouldNotBeNull()
    }

    test("scheduleEngine throws when this runtime has no provider configured") {
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config)
        val dir = createTempDirectory("schedule-engine-test")

        shouldThrow<IllegalArgumentException> {
            rt.scheduleEngine(TaskStore(dir.resolve("tasks.json")), RunLog(dir.resolve("runs.jsonl")), NoopNotifier)
        }
    }

    test("skills lists what's actually on disk in skillsDir") {
        val dir = createTempDirectory("sophi-sdk-skills-test")
        dir.resolve("greet.md").writeText("---\ntitle: Greet\ndescription: says hi\n---\n\nSay hello.")
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config, skillsDir = dir)

        val skills = rt.skills()

        skills.map { it.first } shouldBe listOf("greet")
        skills.single().second.metadata.title shouldBe "Greet"
    }

    test("installSkill installs into skillsDir, then skills() finds it") {
        val skillsDir = createTempDirectory("sophi-sdk-skills-test")
        val source = createTempDirectory("sophi-sdk-skills-source")
        val skillFolder = source.resolve("my-skill").also { it.createDirectories() }
        skillFolder.resolve("SKILL.md").writeText("---\nname: My Skill\ndescription: does things\n---\n\nBody.")
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config, skillsDir = skillsDir)

        val result = rt.installSkill(source.toString())

        result.installed shouldBe listOf("my-skill")
        rt.skills().map { it.first } shouldBe listOf("my-skill")
    }

    test("removeSkill deletes an installed skill, then skills() no longer finds it") {
        val skillsDir = createTempDirectory("sophi-sdk-skills-test")
        skillsDir.resolve("temp.md").writeText("---\ntitle: Temp\n---\n\nBody.")
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config, skillsDir = skillsDir)

        val removed = rt.removeSkill("temp")

        removed shouldBe true
        rt.skills() shouldBe emptyList()
    }

    test("removeSkill returns false for an id that doesn't exist") {
        val skillsDir = createTempDirectory("sophi-sdk-skills-test")
        val rt = SophiRuntime(agentLoop, sessionManager, PluginRegistry(), config, skillsDir = skillsDir)

        rt.removeSkill("ghost") shouldBe false
    }

    test("setting RuntimeBuilder.mcpConfigPath (not calling .mcpConfig()) stores the path but does not trigger build()'s auto-connect") {
        val dir = createTempDirectory("sophi-sdk-mcp-test")
        val path = dir.resolve("mcp.json")
        McpConfigWriter().write(path, McpConfig(servers = listOf(McpServerConfig(name = "fs", transport = McpTransport.STDIO, command = listOf("x"), enabled = true))))
        val mcpManager = mockk<McpClientManager>()
        coEvery { mcpManager.connect(any()) } returns emptyList()

        val builder = RuntimeBuilder()
        builder.provider = mockk<LLMProvider>()
        builder.sessionsDir = createTempDirectory("sophi-sdk-test")
        builder.mcpConfigPath = path
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .mcpClientManager(mcpManager)
            .build()

        rt.mcpServers().map { it.name } shouldBe listOf("fs")
        coVerify(exactly = 1) { mcpManager.connect(emptyList()) }
    }
})
