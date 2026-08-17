package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.tools.Tool
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.LearningConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class SophiRuntimeLearningTest : FunSpec({

    test("turn records a failed tool event to the learning tool-events log") {
        val learningHome = createTempDirectory("sophi-learning-test")

        val boomTool = object : Tool {
            override val name = "boom"
            override val description = "throws"
            override val parametersJson = """{"type":"object","properties":{}}"""
            override suspend fun execute(argumentsJson: String): String = error("nope")
        }

        val provider = mockk<LLMProvider>()
        var call = 0
        every { provider.stream(any()) } answers {
            call++
            if (call == 1)
                LLMResponse.ToolUse(listOf(ToolCall("c1", "boom", "{}")), TokenUsage(1, 1)).toStreamFlow()
            else
                LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        }

        val builder = RuntimeBuilder()
        builder.provider = provider
        builder.sessionsDir = createTempDirectory("sophi-sdk-learning-sessions")
        val rt = builder
            .tool(boomTool)
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .learning(LearningConfig(home = learningHome, scope = "/p"))
            .build()

        val sessionId = rt.newSession()
        rt.turn(sessionId, "go")

        JsonlLog(learningHome.resolve("tool-events.jsonl")).readAll().single() shouldContain
            "\"success\":false"
    }
})
