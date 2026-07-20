package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flow

private class NoopInput : InputSource {
    override suspend fun readLine(): String? = null
    override suspend fun awaitEsc() { kotlinx.coroutines.delay(Long.MAX_VALUE) }
    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit) {
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }
}

class TurnControllerContextTest : FunSpec({
    fun rig(provider: LLMProvider, contextProvider: suspend (dev.sophi.core.session.AgentSession, String) -> String?,
            onSettled: suspend (String, String, Throwable?) -> Unit = { _, _, _ -> }): TurnController {
        val sm = FileSessionManager(tempdir().toPath())
        val loop = AgentLoop(provider, ToolRegistry(), sm)
        return TurnController(
            loop, AgentConfig(model = "m", systemPrompt = "BASE"), NoopInput(),
            LiveRegion(StringBuilder()) { 80 },
            contextProvider = contextProvider, onTurnSettled = onSettled
        ) { }
    }

    test("contextProvider output is appended to the turn's system prompt; base config untouched") {
        val provider = mockk<LLMProvider>()
        val captured = slot<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(capture(captured)) } returns flow { emit("ok") }
        val sm = FileSessionManager(tempdir().toPath())
        val session = sm.create()
        val tc = rig(provider, { _, input -> "<memory_context>$input</memory_context>" })
        tc.runTurn(session, "hello")
        captured.captured.systemPrompt shouldContain "BASE"
        captured.captured.systemPrompt shouldContain "<memory_context>hello</memory_context>"
    }

    test("null and throwing contextProviders leave the prompt unchanged") {
        val provider = mockk<LLMProvider>()
        val captured = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(capture(captured)) } returns flow { emit("ok") }
        val sm = FileSessionManager(tempdir().toPath())
        rig(provider, { _, _ -> null }).runTurn(sm.create(), "a")
        rig(provider, { _, _ -> error("kaput") }).runTurn(sm.create(), "b")
        captured[0].systemPrompt shouldBe "BASE"
        captured[1].systemPrompt shouldBe "BASE"
    }

    test("onTurnSettled receives the user input and the assistant reply") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flow { emit("the "); emit("reply") }
        var settled: Triple<String, String, Throwable?>? = null
        val sm = FileSessionManager(tempdir().toPath())
        val tc = rig(provider, { _, _ -> null }, { u, r, e -> settled = Triple(u, r, e) })
        tc.runTurn(sm.create(), "question")
        settled shouldBe Triple("question", "the reply", null)
    }
})
