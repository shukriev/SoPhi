package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class RuntimeBuilderGuardianTest : FunSpec({
    class Rm : Tool {
        var executed = 0
        override val name = "rm"
        override val description = "fake"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
        override suspend fun execute(argumentsJson: String): String { executed++; return "ok" }
    }

    val reviews = mutableListOf<CompletionRequest>()
    fun builder(rm: Rm): RuntimeBuilder {
        val rounds = listOf<LLMResponse>(
            LLMResponse.ToolUse(listOf(ToolCall("c1", "rm", "{}")), TokenUsage(1, 0)),
            LLMResponse.Text("done", TokenUsage(1, 1)),
        )
        var n = 0
        reviews.clear()
        val llm = mockk<LLMProvider>()
        every { llm.stream(any()) } answers { rounds[n++].toStreamFlow() }
        coEvery { llm.complete(any()) } answers { reviews += firstArg<CompletionRequest>(); LLMResponse.Text("DENY: x", TokenUsage(1, 1)) }
        return RuntimeBuilder().apply {
            provider = llm
            sessionsDir = tempdir().toPath()
            model = "test-model"
            confirmationPolicy(ConfirmationPolicy.ALLOW_ALL)
        }.contextWindowTokens(100_000).tool(rm)
    }

    test("guardian() reviews with the session's model and its DENY stops the call") {
        val rm = Rm()
        val runtime = builder(rm).guardian().build()
        runBlocking { runtime.turn(runtime.newSession(), "rm it") }

        rm.executed shouldBe 0
        reviews.single().model shouldBe "test-model"
    }

    test("guardian(model) reviews with the named model") {
        val runtime = builder(Rm()).guardian("other").build()
        runBlocking { runtime.turn(runtime.newSession(), "rm it") }

        reviews.single().model shouldBe "other"
    }

    test("without guardian() nothing is reviewed") {
        val rm = Rm()
        val runtime = builder(rm).build()
        runBlocking { runtime.turn(runtime.newSession(), "rm it") }

        rm.executed shouldBe 1
        reviews shouldBe emptyList()
    }
})
