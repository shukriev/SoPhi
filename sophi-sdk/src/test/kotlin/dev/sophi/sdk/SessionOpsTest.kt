package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.tools.ConfirmationPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.readText

class SessionOpsTest : FunSpec({
    fun builder(llm: LLMProvider) = RuntimeBuilder().apply {
        provider = llm
        sessionsDir = tempdir().toPath()
        model = "test-model"
        confirmationPolicy(ConfirmationPolicy.ALLOW_ALL)
    }.contextWindowTokens(100_000)

    fun AgentSession.pairs(n: Int) = repeat(n) { append(EntryRole.USER, "msg $it"); append(EntryRole.ASSISTANT, "resp $it") }

    test("checkout moves the tip and saves it") {
        val runtime = builder(ScriptedProvider()).build()
        val s = AgentSession(id = "s1").also { it.pairs(2) }
        val first = s.branch().first().id
        runtime.checkout(s, first) shouldBe emptyList()
        runtime.sessionManager.load(s.id).branch().last().id shouldBe first
    }

    test("checkout of an unknown entry fails") {
        val runtime = builder(ScriptedProvider()).build()
        shouldThrow<IllegalArgumentException> { runtime.checkout(AgentSession(id = "s1").also { it.pairs(1) }, "nope") }
    }

    test("branch is the session's branch") {
        val runtime = builder(ScriptedProvider()).build()
        val s = AgentSession(id = "s1").also { it.pairs(1) }
        runtime.branch(s) shouldBe s.branch()
    }

    test("compact shortens the branch and saves it") {
        val runtime = builder(ScriptedProvider("a summary")).build()
        val s = AgentSession(id = "s1").also { it.pairs(4) }
        val compacted = runtime.compact(s)
        (compacted.branch().size < 8) shouldBe true
        runtime.sessionManager.load(compacted.id).branch().size shouldBe compacted.branch().size
    }

    test("checkout with files restores a file the later round changed") {
        val root = tempdir().toPath()
        val rounds = listOf<LLMResponse>(
            LLMResponse.ToolUse(listOf(ToolCall("c1", "write_file", """{"path":"x.txt","content":"hi"}""")), TokenUsage(1, 0)),
            LLMResponse.Text("done", TokenUsage(1, 1)),
        )
        var n = 0
        val llm = mockk<LLMProvider>()
        every { llm.stream(any()) } answers { rounds[n++].toStreamFlow() }
        val runtime = builder(llm).builtinTools(root).checkpoints(home = tempdir().toPath()).build()

        val id = runtime.newSession()
        val before = runtime.sessionManager.load(id).also { it.pairs(1); runtime.sessionManager.save(it) }.branch().last().id
        runtime.turn(id, "write it")
        root.resolve("x.txt").readText() shouldBe "hi"

        runtime.checkout(runtime.sessionManager.load(id), before, files = true)
        (root.resolve("x.txt").toFile().exists()) shouldBe false
    }
})
