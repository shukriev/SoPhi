package dev.sophi.sdk.acp

import dev.sophi.sdk.rpc.RpcEvent
import dev.sophi.sdk.rpc.RpcServer
import dev.sophi.sdk.rpc.decodeRpcEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject

private const val ALLOW = "allow"
private const val REJECT = "reject"

/**
 * `sophi --mode acp`: the Agent Client Protocol (protocol version 1; field names from the v1 schema of
 * agentclientprotocol/agent-client-protocol, release schema-v1.30.0) on top of an [RpcServer]. ACP requests
 * become RPC commands, RPC events become `session/update` notifications, and confirmations become
 * `session/request_permission` requests. Wire [relay] as the RPC server's `out`; [out] gets one JSON-RPC
 * message per line, [log] what an ACP client has no place for (stderr).
 */
class AcpServer(private val rpc: RpcServer, private val log: (String) -> Unit = {}, private val out: (String) -> Unit) {
    private val lock = Any()
    private val commands = Channel<String>(Channel.UNLIMITED)
    /** Guarded by [lock], as is every write to [out]. */
    private var opened = false
    private var prompt: JsonElement? = null // the session/prompt waiting for its turn to end
    private var nextId = 0
    private val asked = mutableMapOf<String, String>() // our request id -> the call it asks about
    private val shown = mutableSetOf<String>() // calls already sent as a tool_call

    /** Serves [input] until it closes, then lets the RPC server finish its turn. */
    suspend fun run(input: ReceiveChannel<String>) = coroutineScope {
        val server = launch { rpc.run(commands) }
        for (line in input) if (line.isNotBlank()) handle(line)
        commands.close()
        server.join()
    }

    private fun handle(line: String) {
        val msg = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return fail(JsonNull, -32700, "parse error")
        val id = msg["id"]
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        when (msg["method"]?.jsonPrimitive?.contentOrNull) {
            null -> id?.let { answered(it.jsonPrimitive.content, msg) }
            "initialize" -> respond(id, buildJsonObject {
                put("protocolVersion", 1)
                putJsonObject("agentCapabilities") {
                    put("loadSession", false)
                    putJsonObject("promptCapabilities") { put("image", false); put("audio", false); put("embeddedContext", false) }
                }
                putJsonArray("authMethods") {}
                putJsonObject("agentInfo") { put("name", "sophi"); put("version", AcpServer::class.java.`package`?.implementationVersion ?: "dev") }
            })
            "session/new" -> {
                val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull
                if (cwd != null && java.nio.file.Path.of(cwd).toAbsolutePath().normalize() != java.nio.file.Path.of("").toAbsolutePath()) {
                    log("acp: the client asked for $cwd; Sophi works in the directory it was started in, ${System.getProperty("user.dir")}")
                }
                // The first session is the one Sophi started with (so --session resumes); later ones are fresh.
                val sid = synchronized(lock) { if (!opened) rpc.sessionId.also { opened = true } else rpc.newSession() }
                    ?: return fail(id, -32603, "a prompt is running")
                respond(id, buildJsonObject { put("sessionId", sid) })
            }
            "session/prompt" -> {
                if (params["sessionId"]?.jsonPrimitive?.contentOrNull != rpc.sessionId) return fail(id, -32602, "unknown session")
                val text = (params["prompt"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { b ->
                    val block = b as? JsonObject ?: return@mapNotNull null
                    when (block["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> block["text"]?.jsonPrimitive?.contentOrNull
                        "resource_link" -> block["uri"]?.jsonPrimitive?.contentOrNull // an @-mention: Sophi reads it with its own tools
                        else -> null
                    }
                }.joinToString("\n")
                if (text.isBlank()) return fail(id, -32602, "the prompt has no text")
                synchronized(lock) {
                    if (prompt != null) return fail(id, -32603, "a prompt is already running")
                    prompt = id
                }
                command(buildJsonObject { put("type", "prompt"); put("text", text) })
            }
            "session/cancel" -> command(buildJsonObject { put("type", "abort") })
            else -> if (id != null) fail(id, -32601, "method not found: ${msg["method"]}")
        }
    }

    /** The client's answer to a session/request_permission; anything but our "allow" option is a no. */
    private fun answered(requestId: String, msg: JsonObject) {
        val callId = synchronized(lock) { asked.remove(requestId) } ?: return log("acp: a response to unknown request $requestId")
        val outcome = (msg["result"] as? JsonObject)?.get("outcome") as? JsonObject
        val allowed = outcome?.get("outcome")?.jsonPrimitive?.contentOrNull == "selected" &&
            outcome["optionId"]?.jsonPrimitive?.contentOrNull == ALLOW
        command(buildJsonObject { put("type", "confirm"); put("callId", callId); put("approved", allowed) })
    }

    private fun command(c: JsonObject) { commands.trySend(c.toString()) }

    /** The RPC server's `out`: maps each of its events onto ACP. */
    fun relay(line: String) {
        when (val e = decodeRpcEvent(line)) {
            is RpcEvent.Token -> update(chunk("agent_message_chunk", e.text))
            is RpcEvent.ReasoningToken -> update(chunk("agent_thought_chunk", e.text))
            is RpcEvent.ConfirmationRequested -> e.requests.forEach { r ->
                showTool(r.callId, r.toolName, r.argumentsJson, "pending")
                val requestId = synchronized(lock) { "sophi-${nextId++}".also { asked[it] = r.callId } }
                send(buildJsonObject {
                    put("id", requestId)
                    put("method", "session/request_permission")
                    putJsonObject("params") {
                        put("sessionId", rpc.sessionId)
                        putJsonObject("toolCall") {
                            put("toolCallId", r.callId)
                            r.preview?.let { putJsonArray("content") { addJsonObject { put("type", "content"); put("content", text(it)) } } }
                        }
                        putJsonArray("options") {
                            addJsonObject { put("optionId", ALLOW); put("name", "Allow"); put("kind", "allow_once") }
                            addJsonObject { put("optionId", REJECT); put("name", "Reject"); put("kind", "reject_once") }
                        }
                    }
                })
            }
            is RpcEvent.ToolCallStarted -> showTool(e.callId, e.name, e.argsJson, "in_progress")
            is RpcEvent.ToolCallFinished -> update(buildJsonObject {
                put("sessionUpdate", "tool_call_update")
                put("toolCallId", e.callId)
                put("status", if (e.isError) "failed" else "completed")
                putJsonArray("content") { addJsonObject { put("type", "content"); put("content", text(e.result)) } }
            })
            is RpcEvent.TurnEnded -> {
                val id = synchronized(lock) { prompt.also { prompt = null; shown.clear() } } ?: return
                if (e.stopReason == "Error") return fail(id, -32603, e.reply)
                respond(id, buildJsonObject { put("stopReason", stopReason(e.stopReason)) })
            }
            is RpcEvent.Error -> log("acp: ${e.message}")
            else -> {}
        }
    }

    /** A tool_call the first time a call shows up (asked about or started), a tool_call_update after that. */
    private fun showTool(callId: String, name: String, argsJson: String, status: String) {
        val first = synchronized(lock) { shown.add(callId) }
        update(buildJsonObject {
            put("sessionUpdate", if (first) "tool_call" else "tool_call_update")
            put("toolCallId", callId)
            put("status", status)
            if (first) {
                put("title", name)
                put("rawInput", runCatching { Json.parseToJsonElement(argsJson) }.getOrElse { JsonPrimitive(argsJson) })
            }
        })
    }

    private fun update(u: JsonObject) = send(buildJsonObject {
        put("method", "session/update")
        putJsonObject("params") { put("sessionId", rpc.sessionId); put("update", u) }
    })

    private fun respond(id: JsonElement?, result: JsonObject) {
        if (id != null) send(buildJsonObject { put("id", id); put("result", result) })
    }

    private fun fail(id: JsonElement?, code: Int, message: String) {
        if (id != null) send(buildJsonObject { put("id", id); putJsonObject("error") { put("code", code); put("message", message) } })
    }

    private fun send(msg: JsonObject) = synchronized(lock) {
        out(JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0")) + msg).toString())
    }
}

private fun text(s: String) = buildJsonObject { put("type", "text"); put("text", s) }
private fun chunk(kind: String, s: String) = buildJsonObject { put("sessionUpdate", kind); put("content", text(s)) }

/** RPC stop reasons (`Completed` or a TurnStopReason name) as ACP's StopReason. */
private fun stopReason(rpc: String) = when (rpc) {
    "UserStopped" -> "cancelled"
    "OutputTruncated" -> "max_tokens"
    "ToolRoundCeiling" -> "max_turn_requests"
    else -> "end_turn"
}
