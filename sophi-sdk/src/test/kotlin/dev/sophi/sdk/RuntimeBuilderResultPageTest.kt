package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.tools.Tool
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.exists

class RuntimeBuilderResultPageTest : FunSpec({
    test("every runtime offers result_read, pages at resultPageChars, and keeps results next to its sessions") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "big", "{}")))),
            flowOf(StreamEvent.Content("done")),
        )
        val sessions = tempdir().toPath()
        val runtime = RuntimeBuilder().apply {
            this.provider = provider
            sessionsDir = sessions
            resultPageChars = 500
            model = "test-model"
        }.contextWindowTokens(100_000).tool(object : Tool {
            override val name = "big"
            override val description = "long output"
            override val parametersJson = """{"type":"object","properties":{}}"""
            override suspend fun execute(argumentsJson: String) = "y".repeat(1_200)
        }).build()

        runtime.toolNames() shouldContain "result_read"
        val id = runBlocking { runtime.newSession() }
        runBlocking(SessionIdContext(id)) { runtime.turn(id, "go") }
        val shown = runtime.sessionManager.load(id).branch().first { it.role == EntryRole.TOOL_RESULT }.content
        shown shouldContain """result_read("r1", 2) for the next page of 3"""
        sessions.resolve("$id.results/r1.txt").exists() shouldBe true
    }
})
