package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.ImageRef
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.session.images
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class AgentLoopImagesTest : FunSpec({
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")
    val shot = ImageRef("/tmp/sophi-test-shot.png", "image/png")
    val requests = mutableListOf<CompletionRequest>()
    val saved = mutableListOf<AgentSession>()

    fun loop(supportsImages: Boolean): AgentLoop {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        requests.clear(); saved.clear()
        every { provider.stream(any()) } answers {
            requests += firstArg<CompletionRequest>()
            LLMResponse.Text("seen", TokenUsage(1, 1)).toStreamFlow()
        }
        every { sm.save(any()) } answers { saved += firstArg<AgentSession>() }
        return AgentLoop(provider, ToolRegistry(), sm, contextWindowTokens = 100_000, supportsImages = { supportsImages })
    }

    test("images reach the provider when supportsImages") {
        loop(true).turn(AgentSession(id = "s"), "look", config, images = listOf(shot))
        requests.single().messages.last().images shouldBe listOf(shot)
    }

    test("no images reach the provider when not, but the saved entry still has them") {
        loop(false).turn(AgentSession(id = "s"), "look", config, images = listOf(shot))
        requests.single().messages.last().images shouldBe emptyList()
        saved.last().branch().first { it.role == EntryRole.USER }.images() shouldBe listOf(shot)
    }

    test("a saved image replays on the next turn only to a model that can see") {
        val l = loop(true)
        val s = l.turn(AgentSession(id = "s"), "look", config, images = listOf(shot))
        l.turn(s, "and now?", config)
        requests[1].messages.first { it.content == "look" }.images shouldBe listOf(shot)

        val text = loop(false)
        text.turn(s, "again?", config)
        requests.single().messages.first { it.content == "look" }.images shouldBe emptyList()
    }

    test("deleted image file: the turn still runs and the request carries the reference for the provider to note") {
        val gone = ImageRef("/nope/deleted.png", "image/png")
        loop(true).turn(AgentSession(id = "s"), "look", config, images = listOf(gone))
        requests.single().messages.last().images shouldBe listOf(gone)
        saved.last().branch().last().content shouldBe "seen"
    }
})
