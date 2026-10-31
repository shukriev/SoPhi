package dev.sophi.core.context

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.tools.currentTodos
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class ContextCompactorTodoTest : FunSpec({
    val provider = mockk<LLMProvider>()
    coEvery { provider.complete(any()) } returns LLMResponse.Text("S", TokenUsage(5, 3))
    val compactor = ContextCompactor(provider)
    val config = AgentConfig(model = "m")

    fun AgentSession.chat(n: Int) = apply { repeat(n) { append(EntryRole.USER, "q$it"); append(EntryRole.ASSISTANT, "a$it") } }

    test("todo survives two compactions") {
        val s = AgentSession("s").apply {
            append(EntryRole.USER, "plan it")
            append(EntryRole.TOOL_RESULT, "[ ] a", mapOf("replay" to "false", "toolCallId" to "c", "toolName" to "todo"))
            append(EntryRole.ASSISTANT, "ok")
        }.chat(6)
        val once = runBlocking { compactor.compact(s, config) }
        val summary = once.branch().first()
        summary.content shouldContain "Current todo list:\n[ ] a"
        summary.metadata["_todo"] shouldNotBe null
        val twice = runBlocking { compactor.compact(once.chat(6), config) }
        twice.branch().first().content shouldContain "[ ] a"
        currentTodos(twice)!!.single().text shouldBe "a"
    }

    test("no todo, no section") {
        val once = runBlocking { compactor.compact(AgentSession("s").chat(8), config) }
        once.branch().first().content shouldNotContain "Current todo list"
    }
})
