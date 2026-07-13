package dev.sophi.web

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import dev.sophi.core.session.SessionManager
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.web.api.FeedbackRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus

class FeedbackEndpointTest : FunSpec({
    val sessionManager = mockk<SessionManager>()
    val agentLoop = mockk<AgentLoop>()
    val config = AgentConfig(model = "test-model")

    fun sessionWithAssistantReply(id: String) = AgentSession(
        id, initialEntries = listOf(
            SessionEntry("e1", null, EntryRole.USER, "hi", 0L),
            SessionEntry("e2", "e1", EntryRole.ASSISTANT, "hello!", 0L)
        )
    )

    test("feedback with valid body records preference and returns 200") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = learning)
        every { sessionManager.load("s1") } returns sessionWithAssistantReply("s1")

        val response = controller.feedback("s1", FeedbackRequest(polarity = "negative", reason = "too verbose"))

        response.statusCode shouldBe HttpStatus.OK
        response.body?.get("status") shouldBe "ok"
        learning.preferenceStore.forSession("s1") shouldHaveSize 1
        learning.preferenceStore.forSession("s1").single().entryIndex shouldBe 1
    }

    test("feedback defaults entryIndex to last non-replay assistant entry") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = learning)
        every { sessionManager.load("s2") } returns sessionWithAssistantReply("s2")

        controller.feedback("s2", FeedbackRequest(polarity = "positive"))

        learning.preferenceStore.forSession("s2").single().entryIndex shouldBe 1
    }

    test("feedback with explicit entryIndex uses given index") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = learning)
        every { sessionManager.load("s3") } returns sessionWithAssistantReply("s3")

        controller.feedback("s3", FeedbackRequest(entryIndex = 0, polarity = "negative"))

        learning.preferenceStore.forSession("s3").single().entryIndex shouldBe 0
    }

    test("feedback with invalid polarity returns 400") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = learning)

        val response = controller.feedback("s1", FeedbackRequest(polarity = "meh"))

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    test("feedback returns 404 for unknown session") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = learning)
        every { sessionManager.load("bad") } throws IllegalArgumentException("not found")

        val response = controller.feedback("bad", FeedbackRequest(polarity = "positive"))

        response.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    test("feedback returns 503 when learning plugin is absent") {
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = null)
        every { sessionManager.load("s1") } returns sessionWithAssistantReply("s1")

        val response = controller.feedback("s1", FeedbackRequest(polarity = "positive"))

        response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
    }

    test("invalid polarity is checked before the session lookup: 400, not 404, for an unknown session") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = learning)
        every { sessionManager.load("does-not-exist") } throws IllegalArgumentException("not found")

        val response = controller.feedback("does-not-exist", FeedbackRequest(polarity = "meh"))

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    test("learning-disabled is checked before the session lookup: 503, not 404, for an unknown session") {
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = null)
        every { sessionManager.load("does-not-exist") } throws IllegalArgumentException("not found")

        val response = controller.feedback("does-not-exist", FeedbackRequest(polarity = "positive"))

        response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
    }

    test("feedback returns 400 when there is no entry to rate") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val controller = AgentController(sessionManager, agentLoop, config, learningPlugin = learning)
        every { sessionManager.load("empty") } returns AgentSession("empty")

        val response = controller.feedback("empty", FeedbackRequest(polarity = "positive"))

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
    }
})
