package dev.sophi.hub

import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

// encodeDefaults = true — HubEvent.timestamp has a default value; without this, Json omits it
// when unset, and round-tripping would evaluate a fresh (different) default on decode.
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class HubProtocolTest : FunSpec({
    test("every HubEvent case round-trips through JSON via the sealed base type") {
        val events: List<HubEvent> = listOf(
            HubEvent.SessionRegistered("s1", "My session", 1234L, "/repo"),
            HubEvent.SessionRegistered("s1", null, 1234L, "/repo"),
            HubEvent.SessionClosed("s1"),
            HubEvent.TurnStarted("s1", "hello"),
            HubEvent.TurnEnded("s1"),
            HubEvent.Token("s1", "hi"),
            HubEvent.ReasoningToken("s1", "thinking"),
            HubEvent.ToolCallStarted("s1", "bash", """{"command":"ls"}"""),
            HubEvent.ToolCallFinished("s1", "bash", "ok", isError = false),
            HubEvent.ConfirmationRequested("s1", listOf(ConfirmationRequest("c1", "bash", "{}", RiskLevel.DESTRUCTIVE))),
            HubEvent.ConfirmationResolved("s1")
        )
        events.forEach { event ->
            val encoded = json.encodeToString(HubEvent.serializer(), event)
            json.decodeFromString(HubEvent.serializer(), encoded) shouldBe event
        }
    }

    test("every HubCommand case round-trips through JSON via the sealed base type") {
        val commands: List<HubCommand> = listOf(
            HubCommand.SendMessage("s1", "hello from companion"),
            HubCommand.ConfirmationResponse("s1", "c1", approved = true),
            HubCommand.ConfirmationResponse("s1", "c1", approved = false)
        )
        commands.forEach { command ->
            val encoded = json.encodeToString(HubCommand.serializer(), command)
            json.decodeFromString(HubCommand.serializer(), encoded) shouldBe command
        }
    }

    test("sessionId is readable through the sealed base type for every HubEvent case") {
        val event: HubEvent = HubEvent.Token("s1", "hi")
        event.sessionId shouldBe "s1"
    }
})
