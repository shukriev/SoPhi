package dev.sophi.gateway.discord

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** GUILDS (to see the server it joined) and DIRECT_MESSAGES. No privileged intents: DMs carry their content. */
const val DISCORD_INTENTS = (1 shl 0) or (1 shl 12)

sealed interface DiscordAction {
    data class Send(val json: String) : DiscordAction
    data class StartHeartbeat(val intervalMs: Long) : DiscordAction
    data object HeartbeatAck : DiscordAction
    data class Reconnect(val resume: Boolean) : DiscordAction
    data class Fatal(val reason: String) : DiscordAction
    data class Dispatch(val type: String, val data: JsonObject) : DiscordAction
}

/** Discord's Gateway protocol as pure state: payloads in, what to do out. The socket is [DiscordClient]'s. */
class DiscordSession(private val token: String, private val intents: Int = DISCORD_INTENTS) {
    var sessionId: String? = null; private set
    var resumeUrl: String? = null; private set
    var seq: Long? = null; private set

    fun onPayload(json: String): List<DiscordAction> {
        val p = discordJson.parseToJsonElement(json).jsonObject
        val d = p["d"]
        return when (p["op"]?.jsonPrimitive?.int) {
            0 -> {
                (p["s"] as? JsonPrimitive)?.longOrNull?.let { seq = it }
                val type = p["t"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = d as? JsonObject ?: JsonObject(emptyMap())
                if (type == "READY") {
                    sessionId = data["session_id"]?.jsonPrimitive?.contentOrNull
                    resumeUrl = data["resume_gateway_url"]?.jsonPrimitive?.contentOrNull
                }
                listOf(DiscordAction.Dispatch(type, data))
            }
            1 -> listOf(DiscordAction.Send(heartbeat()))
            7 -> listOf(DiscordAction.Reconnect(resume = true))
            9 -> {
                val resumable = (d as? JsonPrimitive)?.booleanOrNull == true
                if (!resumable) clear()
                listOf(DiscordAction.Reconnect(resumable))
            }
            10 -> listOf(
                DiscordAction.StartHeartbeat(d!!.jsonObject["heartbeat_interval"]!!.jsonPrimitive.long),
                DiscordAction.Send(if (sessionId != null) resume() else identify()),
            )
            11 -> listOf(DiscordAction.HeartbeatAck)
            else -> emptyList()
        }
    }

    fun onClosed(code: Int): DiscordAction = when (code) {
        4004 -> DiscordAction.Fatal("Discord didn't accept this token.")
        4014 -> DiscordAction.Fatal("Discord refused the intents SoPhi asks for.")
        4007, 4009, in 4010..4013 -> { clear(); DiscordAction.Reconnect(resume = false) }
        else -> DiscordAction.Reconnect(resume = sessionId != null)
    }

    fun heartbeat(): String = buildJsonObject { put("op", 1); put("d", seq?.let(::JsonPrimitive) ?: JsonNull) }.toString()

    private fun clear() { sessionId = null; resumeUrl = null; seq = null }

    private fun identify() = buildJsonObject {
        put("op", 2)
        putJsonObject("d") {
            put("token", token)
            put("intents", intents)
            putJsonObject("properties") { put("os", System.getProperty("os.name").orEmpty()); put("browser", "sophi"); put("device", "sophi") }
        }
    }.toString()

    private fun resume() = buildJsonObject {
        put("op", 6)
        putJsonObject("d") { put("token", token); put("session_id", sessionId); put("seq", seq) }
    }.toString()
}
