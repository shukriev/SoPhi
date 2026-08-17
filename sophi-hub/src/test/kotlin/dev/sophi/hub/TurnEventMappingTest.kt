package dev.sophi.hub

import dev.sophi.core.agent.TurnEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TurnEventMappingTest : FunSpec({
    val fixedTimestamp = 1_700_000_000_000L

    test("Token maps to HubEvent.Token") {
        TurnEvent.Token("hi").toHubEvent("s1", fixedTimestamp) shouldBe HubEvent.Token("s1", "hi", fixedTimestamp)
    }
    test("ReasoningToken maps to HubEvent.ReasoningToken") {
        TurnEvent.ReasoningToken("thinking").toHubEvent("s1", fixedTimestamp) shouldBe
            HubEvent.ReasoningToken("s1", "thinking", fixedTimestamp)
    }
    test("ToolCallStarted maps to HubEvent.ToolCallStarted") {
        TurnEvent.ToolCallStarted("bash", "{}").toHubEvent("s1", fixedTimestamp) shouldBe
            HubEvent.ToolCallStarted("s1", "bash", "{}", fixedTimestamp)
    }
    test("ToolCallFinished maps to HubEvent.ToolCallFinished") {
        TurnEvent.ToolCallFinished("bash", "ok", isError = true, durationMillis = 5)
            .toHubEvent("s1", fixedTimestamp) shouldBe
            HubEvent.ToolCallFinished("s1", "bash", "ok", isError = true, timestamp = fixedTimestamp)
    }
    test("ConfirmationStarted has no HubEvent equivalent") {
        TurnEvent.ConfirmationStarted(listOf("bash")).toHubEvent("s1") shouldBe null
    }
    test("ConfirmationFinished has no HubEvent equivalent") {
        TurnEvent.ConfirmationFinished.toHubEvent("s1") shouldBe null
    }
    test("toHubEvent defaults timestamp to roughly now when not specified") {
        val before = System.currentTimeMillis()
        val event = TurnEvent.Token("hi").toHubEvent("s1")
        val after = System.currentTimeMillis()
        (event!!.timestamp in before..after) shouldBe true
    }
})
