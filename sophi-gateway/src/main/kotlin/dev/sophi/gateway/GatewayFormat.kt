package dev.sophi.gateway

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The gateway contract: how anything outside SoPhi's own window (a chat service, later a paired phone) reaches
 * SoPhi's chats. A gateway speaks only this; each host (the companion, later the CLI) implements
 * [GatewayHost] over its own chats. Channel modules (sophi-gateway-discord, later others) build on this.
 */
const val GATEWAY_FORMAT_VERSION = 1

/** Who sent it and through which host ("discord:<user id>", later a device id). */
@Serializable
data class GatewayAddress(val personId: String, val gatewayId: String)

@Serializable
sealed class GatewayIn {
    abstract val from: GatewayAddress

    @Serializable @SerialName("message")
    data class Message(override val from: GatewayAddress, val text: String, val photoPath: String? = null, val voice: Boolean = false) : GatewayIn()

    @Serializable @SerialName("new_chat")
    data class NewChat(override val from: GatewayAddress) : GatewayIn()

    @Serializable @SerialName("answer_permission")
    data class AnswerPermission(override val from: GatewayAddress, val key: String, val allow: Boolean) : GatewayIn()

    @Serializable @SerialName("answer_question")
    data class AnswerQuestion(override val from: GatewayAddress, val key: String, val choiceIndex: Int?, val text: String?) : GatewayIn()

    /** The whole plan, as offered: approve runs every step, or cancel discards it. */
    @Serializable @SerialName("answer_plan")
    data class AnswerPlan(override val from: GatewayAddress, val key: String, val approve: Boolean) : GatewayIn()
}

enum class Resolution { BY_THIS_GATEWAY_ALLOWED, BY_THIS_GATEWAY_DENIED, BY_THIS_GATEWAY_ANSWERED, ELSEWHERE }

@Serializable
sealed class GatewayOut {
    abstract val createdAtMs: Long

    @Serializable @SerialName("reply")
    data class Reply(val sessionId: String, val text: String, override val createdAtMs: Long) : GatewayOut()

    /** [canAnswer] false: the host gets a note and the answer stays on the computer. */
    @Serializable @SerialName("permission")
    data class Permission(
        val key: String, val sessionId: String, val chatTitle: String, val action: String,
        val canAnswer: Boolean, val tainted: Boolean, override val createdAtMs: Long,
    ) : GatewayOut()

    @Serializable @SerialName("question")
    data class Question(
        val key: String, val text: String, val choices: List<String>, val allowFreeText: Boolean, override val createdAtMs: Long,
    ) : GatewayOut()

    /** A plan-first plan: nothing ran yet. [steps] read as shown, secrets already masked. */
    @Serializable @SerialName("plan")
    data class Plan(
        val key: String, val sessionId: String, val chatTitle: String, val task: String, val steps: List<String>,
        val canAnswer: Boolean, override val createdAtMs: Long,
    ) : GatewayOut()

    /** A permission, question or plan that is no longer waiting, and who settled it. */
    @Serializable @SerialName("resolved")
    data class Resolved(val key: String, val how: Resolution, override val createdAtMs: Long) : GatewayOut()

    @Serializable @SerialName("notice")
    data class Notice(val title: String, val body: String, override val createdAtMs: Long) : GatewayOut()
}

/** What the host did with a [GatewayIn], decided before the gateway shows anything. */
sealed interface GatewayResult {
    /** [pictureDropped]: SoPhi was busy, so the photo's text steered the running turn without the picture. */
    data class Sent(val sessionId: String, val pictureDropped: Boolean = false) : GatewayResult
    data object NewChatStarted : GatewayResult
    /** [allow] is null for a question; for a plan, true is approved. */
    data class Answered(val allow: Boolean?) : GatewayResult
    data object AlreadyAnswered : GatewayResult
    /** This host may not approve; the card stays on the computer. */
    data object NotAllowed : GatewayResult
}

interface GatewayHost {
    val out: SharedFlow<GatewayOut>
    suspend fun receive(m: GatewayIn): GatewayResult
}

/** One frame on a wire: the version, then the message. */
@Serializable
data class GatewayFrame(val v: Int, val body: JsonElement)

private val gatewayJson = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

fun encodeGatewayIn(m: GatewayIn): String =
    gatewayJson.encodeToString(GatewayFrame.serializer(), GatewayFrame(GATEWAY_FORMAT_VERSION, gatewayJson.encodeToJsonElement(GatewayIn.serializer(), m)))

/** Fails on a version this build doesn't speak, rather than misreading it. */
fun decodeGatewayIn(s: String): Result<GatewayIn> = runCatching {
    val frame = gatewayJson.decodeFromString(GatewayFrame.serializer(), s)
    require(frame.v == GATEWAY_FORMAT_VERSION) { "Unsupported gateway format version ${frame.v}; this SoPhi speaks $GATEWAY_FORMAT_VERSION." }
    gatewayJson.decodeFromJsonElement(GatewayIn.serializer(), frame.body)
}
