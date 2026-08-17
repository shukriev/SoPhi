package dev.sophi.ai.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StreamEventTest : FunSpec({
    test("Usage carries the round's TokenUsage") {
        val event: StreamEvent = StreamEvent.Usage(TokenUsage(inputTokens = 120, outputTokens = 30))

        (event as StreamEvent.Usage).usage.inputTokens shouldBe 120
        event.usage.outputTokens shouldBe 30
    }
})
