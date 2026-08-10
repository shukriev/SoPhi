package dev.sophi.hub

import dev.sophi.core.agent.TurnEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TurnEventMappingTest : FunSpec({
    test("Token maps to HubEvent.Token") {
        TurnEvent.Token("hi").toHubEvent("s1") shouldBe HubEvent.Token("s1", "hi")
    }
    test("ReasoningToken maps to HubEvent.ReasoningToken") {
        TurnEvent.ReasoningToken("thinking").toHubEvent("s1") shouldBe HubEvent.ReasoningToken("s1", "thinking")
    }
    test("ToolCallStarted maps to HubEvent.ToolCallStarted") {
        TurnEvent.ToolCallStarted("bash", "{}").toHubEvent("s1") shouldBe HubEvent.ToolCallStarted("s1", "bash", "{}")
    }
    test("ToolCallFinished maps to HubEvent.ToolCallFinished") {
        TurnEvent.ToolCallFinished("bash", "ok", isError = true, durationMillis = 5)
            .toHubEvent("s1") shouldBe HubEvent.ToolCallFinished("s1", "bash", "ok", isError = true)
    }
    test("ConfirmationStarted has no HubEvent equivalent") {
        TurnEvent.ConfirmationStarted(listOf("bash")).toHubEvent("s1") shouldBe null
    }
    test("ConfirmationFinished has no HubEvent equivalent") {
        TurnEvent.ConfirmationFinished.toHubEvent("s1") shouldBe null
    }
})
