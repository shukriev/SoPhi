package dev.sophi.sdk.rpc

import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.tools.ConfirmationRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lines a client writes to `sophi --mode rpc`; [id] is echoed back in the command's `ack`/`error`. */
@Serializable
sealed class RpcCommand {
    abstract val id: String?

    @Serializable @SerialName("prompt") data class Prompt(val text: String, override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("follow_up") data class FollowUp(val text: String, override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("steer") data class Steer(val text: String, override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("abort") data class Abort(override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("confirm") data class Confirm(val callId: String, val approved: Boolean, override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("compact") data class Compact(override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("branch") data class Branch(override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("checkout") data class Checkout(val entryId: String, val files: Boolean = false, override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("set_model") data class SetModel(val model: String, override val id: String? = null) : RpcCommand()
    @Serializable @SerialName("goal") data class Goal(val text: String, val check: String? = null, override val id: String? = null) : RpcCommand()
}

@Serializable
data class MemoryStatus(val enabled: Boolean, val reason: String? = null)

/** Lines `sophi --mode rpc` writes to stdout, one JSON object each. */
@Serializable
sealed class RpcEvent {
    @Serializable @SerialName("ready") data class Ready(val sessionId: String, val model: String, val provider: String, val memory: MemoryStatus) : RpcEvent()
    @Serializable @SerialName("turn_started") data class TurnStarted(val input: String) : RpcEvent()
    @Serializable @SerialName("token") data class Token(val text: String) : RpcEvent()
    @Serializable @SerialName("reasoning_token") data class ReasoningToken(val text: String) : RpcEvent()
    @Serializable @SerialName("tool_call_started") data class ToolCallStarted(val name: String, val argsJson: String, val callId: String = "") : RpcEvent()
    @Serializable @SerialName("tool_call_finished") data class ToolCallFinished(val name: String, val result: String, val isError: Boolean, val callId: String = "") : RpcEvent()
    @Serializable @SerialName("confirmation_requested") data class ConfirmationRequested(val requests: List<ConfirmationRequest>) : RpcEvent()
    @Serializable @SerialName("confirmation_resolved") data class ConfirmationResolved(val callId: String, val approved: Boolean) : RpcEvent()
    @Serializable @SerialName("steer_applied") data class SteerApplied(val texts: List<String>) : RpcEvent()
    @Serializable @SerialName("follow_up_started") data class FollowUpStarted(val text: String) : RpcEvent()
    @Serializable @SerialName("model_fallback") data class ModelFallback(val from: String, val to: String, val reason: String) : RpcEvent()
    @Serializable @SerialName("usage") data class Usage(val inputTokens: Int, val outputTokens: Int, val generationMillis: Long) : RpcEvent()
    @Serializable @SerialName("plan_ready") data class PlanReady(val planId: String, val steps: Int) : RpcEvent()
    /** [stopReason] is `Completed` or a TurnStopReason name; [goalStatus] is set for goal runs only. */
    @Serializable @SerialName("turn_ended") data class TurnEnded(val reply: String, val stopReason: String, val goalStatus: String? = null) : RpcEvent()
    @Serializable @SerialName("ack") data class Ack(val id: String?, val command: String, val result: String? = null) : RpcEvent()
    @Serializable @SerialName("error") data class Error(val message: String, val id: String? = null) : RpcEvent()
    @Serializable @SerialName("exited") data object Exited : RpcEvent()
}

private val rpcJson = Json {
    classDiscriminator = "type"
    encodeDefaults = false
    ignoreUnknownKeys = true
}

fun decodeRpcCommand(line: String): Result<RpcCommand> =
    runCatching { rpcJson.decodeFromString(RpcCommand.serializer(), line) }

fun decodeRpcEvent(line: String): RpcEvent = rpcJson.decodeFromString(RpcEvent.serializer(), line)

/** Compact JSON never contains a raw newline (kotlinx escapes control characters), so this is one line. */
fun encodeRpcEvent(e: RpcEvent): String = rpcJson.encodeToString(RpcEvent.serializer(), e)

/**
 * Maps a turn event to its wire event, passing text that can carry secrets through [mask].
 * Confirmations map to null: the RPC server emits those itself, since it holds the ConfirmationRequests.
 */
fun TurnEvent.toRpcEvent(mask: (String) -> String): RpcEvent? = when (this) {
    is TurnEvent.Token -> RpcEvent.Token(text)
    is TurnEvent.ReasoningToken -> RpcEvent.ReasoningToken(text)
    is TurnEvent.ToolCallStarted -> RpcEvent.ToolCallStarted(name, mask(argsJson), callId)
    is TurnEvent.ToolCallFinished -> RpcEvent.ToolCallFinished(name, mask(result), isError, callId)
    is TurnEvent.SteerApplied -> RpcEvent.SteerApplied(texts)
    is TurnEvent.FollowUpStarted -> RpcEvent.FollowUpStarted(text)
    is TurnEvent.PlanReady -> RpcEvent.PlanReady(plan.id, plan.steps.size)
    is TurnEvent.RoundUsage -> RpcEvent.Usage(usage.inputTokens, usage.outputTokens, generationMillis)
    is TurnEvent.ModelFallback -> RpcEvent.ModelFallback(from, to, reason)
    is TurnEvent.ConfirmationStarted, TurnEvent.ConfirmationFinished, is TurnEvent.HintedReads -> null
}
