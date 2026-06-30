package dev.sophi.web

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import dev.sophi.core.session.SessionManager
import dev.sophi.core.session.SessionMeta
import dev.sophi.web.api.ChatRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
        coEvery { agentLoop.turn(session, "hi", config) } returns updated

        val response = controller.turn("s1", ChatRequest("hi"))
        response.statusCode shouldBe HttpStatus.OK
        response.body?.reply shouldBe "hello!"
    }

    test("turn returns 404 when session not found") {
        every { sessionManager.load("bad") } throws IllegalArgumentException("not found")
        val response = controller.turn("bad", ChatRequest("hi"))
        response.statusCode shouldBe HttpStatus.NOT_FOUND
    }
})
