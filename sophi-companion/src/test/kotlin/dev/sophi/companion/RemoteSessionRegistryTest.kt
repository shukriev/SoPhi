package dev.sophi.companion

import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import dev.sophi.hub.HubEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RemoteSessionRegistryTest : FunSpec({
    test("SessionRegistered adds the id to remoteSessionIds with state Idle") {
        val registry = RemoteSessionRegistry()
        registry.onEvent(HubEvent.SessionRegistered("s1", "My session", 1L, "/repo"))
        registry.remoteSessionIds() shouldBe setOf("s1")
        registry.stateFlowFor("s1").value shouldBe SessionState.Idle
        registry.titleFor("s1") shouldBe "My session"
    }

    test("TurnStarted then TurnEnded goes Idle -> Running -> Idle") {
        val registry = RemoteSessionRegistry()
        registry.onEvent(HubEvent.SessionRegistered("s1", null, 1L, "/repo"))
        registry.onEvent(HubEvent.TurnStarted("s1", "hi"))
        registry.stateFlowFor("s1").value shouldBe SessionState.Running
        registry.onEvent(HubEvent.TurnEnded("s1"))
        registry.stateFlowFor("s1").value shouldBe SessionState.Idle
    }

    test("ConfirmationRequested sets NeedsConfirmation with the request list; ConfirmationResolved clears it") {
        val registry = RemoteSessionRegistry()
        registry.onEvent(HubEvent.SessionRegistered("s1", null, 1L, "/repo"))
        val requests = listOf(ConfirmationRequest("c1", "bash", "{}", RiskLevel.DESTRUCTIVE))
        registry.onEvent(HubEvent.ConfirmationRequested("s1", requests))
        registry.stateFlowFor("s1").value shouldBe SessionState.NeedsConfirmation(requests)
        registry.onEvent(HubEvent.ConfirmationResolved("s1"))
        registry.stateFlowFor("s1").value shouldBe SessionState.Running
    }

    test("SessionClosed removes the id from remoteSessionIds") {
        val registry = RemoteSessionRegistry()
        registry.onEvent(HubEvent.SessionRegistered("s1", null, 1L, "/repo"))
        registry.onEvent(HubEvent.SessionClosed("s1"))
        registry.remoteSessionIds() shouldBe emptySet()
    }

    test("stateFlowFor an unknown session defaults to Idle without registering it") {
        val registry = RemoteSessionRegistry()
        registry.stateFlowFor("never-seen").value shouldBe SessionState.Idle
        registry.remoteSessionIds() shouldBe emptySet()
    }
})
