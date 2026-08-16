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

    test("TurnStarted delegates to the session's SessionTranscriptBuilder.startTurn") {
        val registry = RemoteSessionRegistry()
        registry.onEvent(HubEvent.SessionRegistered("s1", null, 1L, "/repo"))
        registry.onEvent(HubEvent.TurnStarted("s1", "hello there"))
        registry.transcriptFor("s1").value shouldBe listOf(TranscriptEntry.UserMessage(0, "hello there"))
    }

    test("Token, ReasoningToken, ToolCallStarted, ToolCallFinished all delegate to the builder") {
        val registry = RemoteSessionRegistry()
        registry.onEvent(HubEvent.SessionRegistered("s1", null, 1L, "/repo"))
        registry.onEvent(HubEvent.TurnStarted("s1", "hi"))
        registry.onEvent(HubEvent.ReasoningToken("s1", "thinking"))
        registry.onEvent(HubEvent.Token("s1", "answer"))
        registry.onEvent(HubEvent.ToolCallStarted("s1", "bash", "{}"))
        registry.onEvent(HubEvent.ToolCallFinished("s1", "bash", "ok", isError = false))

        registry.transcriptFor("s1").value shouldBe listOf(
            TranscriptEntry.UserMessage(0, "hi"),
            TranscriptEntry.Reasoning(1, "thinking"),
            TranscriptEntry.Answer(2, "answer"),
            TranscriptEntry.ToolInvocation(3, "bash", "{}", "ok", false)
        )
    }

    test("transcriptFor an unknown session defaults to an empty list without registering it") {
        val registry = RemoteSessionRegistry()
        registry.transcriptFor("never-seen").value shouldBe emptyList()
        registry.remoteSessionIds() shouldBe emptySet()
    }

    test("lastActiveMillisFor reflects the most recent event's timestamp, not just the first") {
        val registry = RemoteSessionRegistry()
        registry.onEvent(HubEvent.SessionRegistered("s1", null, 1L, "/repo", timestamp = 100L))
        registry.lastActiveMillisFor("s1") shouldBe 100L

        registry.onEvent(HubEvent.TurnStarted("s1", "hi", timestamp = 200L))
        registry.lastActiveMillisFor("s1") shouldBe 200L

        registry.onEvent(HubEvent.Token("s1", "hello", timestamp = 300L))
        registry.lastActiveMillisFor("s1") shouldBe 300L
    }

    test("lastActiveMillisFor an unknown session defaults to 0") {
        RemoteSessionRegistry().lastActiveMillisFor("never-seen") shouldBe 0L
    }

    test("ScheduleNotification is ignored — no session registered, no state created") {
        val registry = RemoteSessionRegistry()
        registry.onEvent(HubEvent.ScheduleNotification("task-1", "Sophi: t", "completed — ok"))
        registry.remoteSessionIds() shouldBe emptySet()
    }
})
