package dev.sophi.hub

import dev.sophi.core.tools.ConfirmationRequest
import kotlinx.serialization.Serializable

/** Events a running CLI session publishes to the hub; the companion observes all of them. */
@Serializable
sealed class HubEvent {
    abstract val sessionId: String

    @Serializable
    data class SessionRegistered(
        override val sessionId: String,
        val title: String?,
        val pid: Long,
        val cwd: String
    ) : HubEvent()

    @Serializable
    data class SessionClosed(override val sessionId: String) : HubEvent()

    @Serializable
    data class TurnStarted(override val sessionId: String, val userInput: String) : HubEvent()

    @Serializable
    data class TurnEnded(override val sessionId: String) : HubEvent()

    @Serializable
    data class Token(override val sessionId: String, val text: String) : HubEvent()

    @Serializable
    data class ReasoningToken(override val sessionId: String, val text: String) : HubEvent()

    @Serializable
    data class ToolCallStarted(override val sessionId: String, val name: String, val argsJson: String) : HubEvent()

    @Serializable
    data class ToolCallFinished(
        override val sessionId: String,
        val name: String,
        val result: String,
        val isError: Boolean
    ) : HubEvent()

    @Serializable
    data class ConfirmationRequested(
        override val sessionId: String,
        val requests: List<ConfirmationRequest>
    ) : HubEvent()

    @Serializable
    data class ConfirmationResolved(override val sessionId: String) : HubEvent()
}

/** Commands the companion sends into a specific running CLI session, routed by [sessionId]. */
@Serializable
sealed class HubCommand {
    abstract val sessionId: String

    @Serializable
    data class SendMessage(override val sessionId: String, val text: String) : HubCommand()

    @Serializable
    data class ConfirmationResponse(
        override val sessionId: String,
        val callId: String,
        val approved: Boolean
    ) : HubCommand()
}
