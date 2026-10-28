package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.extensions.HookContext
import kotlin.io.path.createTempDirectory

class SessionTitlerTest : FunSpec({
    test("titles are one clean line: no quotes, label or trailing period, at most 60 chars") {
        cleanTitle("\"FTP migration plan\"") shouldBe "FTP migration plan"
        cleanTitle("Title: Keycloak rollout.\nextra line") shouldBe "Keycloak rollout"
        cleanTitle("  **Weekly report draft**  ") shouldBe "Weekly report draft"
        cleanTitle("x".repeat(80))!!.length shouldBe 60
        cleanTitle("   ") shouldBe null
        cleanTitle(null) shouldBe null
    }

    fun runtime(provider: LLMProvider, onTitled: (String, String) -> Unit = { _, _ -> }): SophiRuntime {
        val b = RuntimeBuilder()
        b.provider = provider
        b.model = "chat-model"
        b.sessionsDir = createTempDirectory("sophi-titles")
        b.memoryHome = createTempDirectory("sophi-titles-mem")
        return b.contextWindowTokens(100_000).autoTitleSessions(onTitled).build()
    }
    fun provider(title: () -> LLMResponse): LLMProvider = mockk<LLMProvider>().also { p ->
        every { p.stream(any()) } answers { LLMResponse.Text("Sure, here's the plan.", TokenUsage(1, 1)).toStreamFlow() }
        coEvery { p.complete(any()) } answers { title() }
    }

    test("an untitled chat gets a title after its first turn, from a no-reasoning call; later turns don't re-title") {
        val requests = mutableListOf<CompletionRequest>()
        val p = provider { LLMResponse.Text("\"FTP migration plan\"", TokenUsage(1, 1)) }
        coEvery { p.complete(capture(requests)) } returns LLMResponse.Text("\"FTP migration plan\"", TokenUsage(1, 1))
        val titled = CompletableDeferred<Pair<String, String>>()
        val rt = runtime(p) { id, t -> titled.complete(id to t) }
        val id = runBlocking { rt.newSession() }
        runBlocking { rt.turn(id, "Help me plan the FTP account migration") }
        runBlocking { withTimeout(5_000) { titled.await() } } shouldBe (id to "FTP migration plan")
        rt.sessionManager.load(id).title shouldBe "FTP migration plan"
        requests.single().reasoningEffort shouldBe "none"
        runBlocking { rt.turn(id, "And the rollback?") }
        Thread.sleep(300)
        coVerify(exactly = 1) { p.complete(any()) }
    }

    test("a session that already has a title is never touched") {
        val p = provider { LLMResponse.Text("Other", TokenUsage(1, 1)) }
        val rt = runtime(p)
        val id = runBlocking { rt.newSession("My own name") }
        runBlocking { rt.turn(id, "hello") }
        Thread.sleep(300)
        coVerify(exactly = 0) { p.complete(any()) }
        rt.sessionManager.load(id).title shouldBe "My own name"
    }

    test("a failed title call leaves the session untitled") {
        val p = provider { LLMResponse.Error("down", null) }
        val rt = runtime(p)
        val id = runBlocking { rt.newSession() }
        runBlocking { rt.turn(id, "hello") }
        Thread.sleep(300)
        rt.sessionManager.load(id).title shouldBe null
    }

    test("a steer in the first turn does not stop the session being titled") {
        val session = AgentSession(id = "s")
        session.append(EntryRole.USER, "Plan the migration")
        session.append(EntryRole.USER, "use port 8080", mapOf("steer" to "true"))
        session.append(EntryRole.ASSISTANT, "Here's the plan.")
        val sm = mockk<SessionManager>()
        every { sm.load("s") } returns session
        every { sm.rename("s", any()) } returns Unit
        val p = provider { LLMResponse.Text("Migration plan", TokenUsage(1, 1)) }
        val titled = CompletableDeferred<String>()
        val plugin = SessionTitlePlugin(p, "m", sm, { _, t -> titled.complete(t) })
        runBlocking {
            plugin.hooks().single().invoke(HookContext("s", userInput = "Plan the migration", assistantReply = "Here's the plan."))
            withTimeout(5_000) { titled.await() } shouldBe "Migration plan"
        }
    }
})
