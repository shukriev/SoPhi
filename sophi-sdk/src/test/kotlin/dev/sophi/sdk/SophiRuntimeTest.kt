package dev.sophi.sdk

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import dev.sophi.core.session.SessionManager
import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.SophiPlugin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

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
        runtime.newSession() shouldBe "sess-1"
    }

    test("newSession passes title to sessionManager") {
        every { sessionManager.create("My Chat") } returns AgentSession("sess-2", "My Chat")
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
        coEvery { agentLoop.turn(session, "hi", config) } returns updated
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
        coEvery { agentLoop.turn(session, "hi", config) } throws RuntimeException("LLM error")

        shouldThrow<RuntimeException> { rt.turn("s1", "hi") }
        log shouldBe listOf(HookPoint.ON_ERROR)
    }

    test("RuntimeBuilder build throws when no provider set") {
        shouldThrow<IllegalArgumentException> { RuntimeBuilder().build() }
    }
})
