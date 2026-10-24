package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.tools.ConfirmationPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.io.path.exists

class RuntimeBuilderCheckpointsTest : FunSpec({
    fun builder(llm: LLMProvider = mockk()) = RuntimeBuilder().apply {
        provider = llm
        sessionsDir = tempdir().toPath()
        model = "test-model"
        confirmationPolicy(ConfirmationPolicy.ALLOW_ALL)
    }.contextWindowTokens(100_000)

    test("builtinTools registers move_file and delete_file") {
        val runtime = builder().builtinTools(tempdir().toPath()).build()
        runtime.toolNames() shouldContain "move_file"
        runtime.toolNames() shouldContain "delete_file"
    }

    test("checkpoints without builtinTools fails to build") {
        val e = shouldThrow<IllegalStateException> { builder().checkpoints(home = tempdir().toPath()).build() }
        e.message shouldBe "checkpoints() needs builtinTools(root)"
    }

    test("with checkpoints on, a write_file round gets a checkpoint and fileRestorer undoes it") {
        val root = tempdir().toPath()
        val rounds = listOf<LLMResponse>(
            LLMResponse.ToolUse(listOf(ToolCall("c1", "write_file", """{"path":"x.txt","content":"hi"}""")), TokenUsage(1, 0)),
            LLMResponse.Text("done", TokenUsage(1, 1)),
        )
        var n = 0
        val llm = mockk<LLMProvider>()
        every { llm.stream(any()) } answers { rounds[n++].toStreamFlow() }
        val runtime = builder(llm).builtinTools(root).checkpoints(home = tempdir().toPath()).build()

        runBlocking {
            val id = runtime.newSession()
            runtime.turn(id, "write it")
            root.resolve("x.txt").exists() shouldBe true

            val session = runtime.sessionManager.load(id)
            val plan = runtime.fileRestorer.plan(session)!!
            runtime.fileRestorer.apply(session, plan)
        }
        root.resolve("x.txt").exists() shouldBe false
    }
})
