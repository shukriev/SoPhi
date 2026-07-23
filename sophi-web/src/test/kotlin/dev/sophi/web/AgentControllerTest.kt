package dev.sophi.web

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import dev.sophi.core.session.SessionManager
import dev.sophi.core.session.SessionMeta
import dev.sophi.extensions.PluginRegistry
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.web.api.ChatRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.springframework.http.HttpStatus

class AgentControllerTest : FunSpec({
    val sessionManager = mockk<SessionManager>()
    val agentLoop = mockk<AgentLoop>()
    val config = AgentConfig(model = "test-model")
    lateinit var controller: AgentController

    beforeEach {
        clearMocks(sessionManager, agentLoop)
        controller = AgentController(sessionManager, agentLoop, config)
    }

    test("createSession returns dto with session id when no title given") {
        every { sessionManager.create(null) } returns AgentSession("s1")
        val dto = controller.createSession(null)
        dto.id shouldBe "s1"
        dto.entryCount shouldBe 0
    }

    test("createSession passes title to sessionManager") {
        every { sessionManager.create("My Chat") } returns AgentSession("s2", "My Chat")
        val dto = controller.createSession("My Chat")
        dto.id shouldBe "s2"
    }

    test("listSessions returns empty list when no sessions") {
        every { sessionManager.list() } returns emptyList()
        controller.listSessions() shouldBe emptyList()
    }

    test("listSessions maps SessionMeta fields to SessionDto") {
        every { sessionManager.list() } returns listOf(SessionMeta("s1", 3, 1000L))
        val dtos = controller.listSessions()
        dtos shouldHaveSize 1
        dtos[0].id shouldBe "s1"
        dtos[0].entryCount shouldBe 3
        dtos[0].lastModifiedMillis shouldBe 1000L
    }

    test("turn returns 200 with last assistant reply") {
        val session = AgentSession("s1")
        val updated = AgentSession(
            "s1", initialEntries = listOf(
                SessionEntry("e1", null, EntryRole.USER, "hi", 0L),
                SessionEntry("e2", "e1", EntryRole.ASSISTANT, "hello!", 0L)
            )
        )
        every { sessionManager.load("s1") } returns session
        coEvery { agentLoop.turn(session, "hi", config, any()) } returns updated

        val response = controller.turn("s1", ChatRequest("hi"))
        response.statusCode shouldBe HttpStatus.OK
        response.body?.reply shouldBe "hello!"
    }

    test("successful turn dispatches AFTER_TURN so learning writes an open outcome") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val registry = PluginRegistry().register(learning)
        val learningController = AgentController(sessionManager, agentLoop, config, registry)

        val session = AgentSession("s-learn")
        val updated = AgentSession(
            "s-learn", initialEntries = listOf(
                SessionEntry("e1", null, EntryRole.ASSISTANT, "hello!", 0L)
            )
        )
        every { sessionManager.load("s-learn") } returns session
        coEvery { agentLoop.turn(session, "hi", config, any()) } returns updated

        learningController.turn("s-learn", ChatRequest("hi"))

        val line = JsonlLog(home.resolve("session-outcomes.jsonl")).readAll().single()
        line shouldContain "\"outcome\":\"open\""
        line shouldContain "\"turns\":1"
    }

    test("turn returns 404 when session not found") {
        every { sessionManager.load("bad") } throws IllegalArgumentException("not found")
        val response = controller.turn("bad", ChatRequest("hi"))
        response.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    test("concurrent turns on the same session are serialized, not interleaved") {
        every { sessionManager.load("s1") } answers { AgentSession("s1") }
        var active = 0
        var maxActive = 0
        coEvery { agentLoop.turn(any(), any(), config, any()) } coAnswers {
            active++
            maxActive = maxOf(maxActive, active)
            kotlinx.coroutines.delay(20)
            active--
            AgentSession(
                "s1", initialEntries = listOf(
                    SessionEntry("e1", null, EntryRole.ASSISTANT, "ok", 0L)
                )
            )
        }

        coroutineScope {
            launch { controller.turn("s1", ChatRequest("a")) }
            launch { controller.turn("s1", ChatRequest("b")) }
        }

        maxActive shouldBe 1
    }

    test("streamTurn returns non-null SseEmitter for valid session") {
        val session = AgentSession("s1")
        every { sessionManager.load("s1") } returns session
        coEvery { agentLoop.streamTurn(any(), any(), any(), any()) } returns session

        controller.streamTurn("s1", "hello").shouldNotBeNull()
    }

    test("streamTurn returns SseEmitter even when session not found") {
        every { sessionManager.load("bad") } throws IllegalArgumentException("not found")
        controller.streamTurn("bad", "hello").shouldNotBeNull()
    }

    test("sseEventFor maps TurnEvent.Token to an unnamed data event") {
        val built = sseEventFor(dev.sophi.core.agent.TurnEvent.Token("hello"))!!.build()
        val text = built.joinToString("") { it.data.toString() }
        text shouldContain "data:hello"
    }

    test("sseEventFor maps TurnEvent.ReasoningToken to a 'reasoning'-named event") {
        val built = sseEventFor(dev.sophi.core.agent.TurnEvent.ReasoningToken("thinking..."))!!.build()
        val text = built.joinToString("") { it.data.toString() }
        text shouldContain "event:reasoning"
        text shouldContain "data:thinking..."
    }

    test("sseEventFor returns null for tool-call events (not forwarded over SSE today)") {
        sseEventFor(dev.sophi.core.agent.TurnEvent.ToolCallStarted("t", "{}")) shouldBe null
    }
})
