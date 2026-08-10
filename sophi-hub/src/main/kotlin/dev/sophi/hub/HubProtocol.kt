package dev.sophi.hub

import dev.sophi.core.tools.ConfirmationRequest
import kotlinx.serialization.Serializable

/** Events a running CLI session publishes to the hub; the companion observes all of them. */
@Serializable
sealed class HubEvent {
    abstract val sessionId: String
    abstract val timestamp: Long

    @Serializable
    data class SessionRegistered(
        override val sessionId: String,
        val title: String?,
        val pid: Long,
        val cwd: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class SessionClosed(
        override val sessionId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class TurnStarted(
        override val sessionId: String,
        val userInput: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class TurnEnded(
        override val sessionId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class Token(
        override val sessionId: String,
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class ReasoningToken(
        override val sessionId: String,
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class ToolCallStarted(
        override val sessionId: String,
        val name: String,
        val argsJson: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class ToolCallFinished(
        override val sessionId: String,
        val name: String,
        val result: String,
        val isError: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class ConfirmationRequested(
        override val sessionId: String,
        val requests: List<ConfirmationRequest>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()

    @Serializable
    data class ConfirmationResolved(
        override val sessionId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HubEvent()
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
