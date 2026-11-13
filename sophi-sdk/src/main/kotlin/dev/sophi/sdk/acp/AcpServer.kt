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
private const val APPROVE_ID = "acp-approve-plan" // the id on our approve_plan, so its error fails the waiting prompt

/**
 * `sophi --mode acp`: the Agent Client Protocol (protocol version 1; field names from the v1 schema of
 * agentclientprotocol/agent-client-protocol, release schema-v1.30.0) on top of an [RpcServer]. ACP requests
 * become RPC commands, RPC events become `session/update` notifications, and confirmations become
 * `session/request_permission` requests. A prompt whose turn held changes as a plan (plan-first) is answered
 * only after the client allows the plan (it replays as a turn) or rejects it (it is cancelled). Wire [relay] as
 * the RPC server's `out`; [out] gets one JSON-RPC message per line, [log] what an ACP client has no place for (stderr).
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
    private var held: RpcEvent.PlanReady? = null // a plan the prompt's turn left, asked about when the turn ends
    private var planAsk: Pair<String, String>? = null // our request id to the plan id, while the client decides
    private var replaying = false // the prompt now waits for approve_plan's replay turn

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
            // While the plan ask is pending there is no turn to abort: cancelling discards the plan.
            "session/cancel" -> synchronized(lock) { planAsk?.second.also { planAsk = null } }
                ?.let { decidePlan(it, allowed = false, cancelled = true) }
                ?: command(buildJsonObject { put("type", "abort") })
            else -> if (id != null) fail(id, -32601, "method not found: ${msg["method"]}")
        }
    }

    /** The client's answer to a session/request_permission; anything but our "allow" option is a no. */
    private fun answered(requestId: String, msg: JsonObject) {
        val outcome = (msg["result"] as? JsonObject)?.get("outcome") as? JsonObject
        val allowed = outcome?.get("outcome")?.jsonPrimitive?.contentOrNull == "selected" &&
            outcome["optionId"]?.jsonPrimitive?.contentOrNull == ALLOW
        synchronized(lock) { planAsk?.takeIf { it.first == requestId }?.second.also { if (it != null) planAsk = null } }
            ?.let { return decidePlan(it, allowed, cancelled = false) }
        val callId = synchronized(lock) { asked.remove(requestId) } ?: return log("acp: a response to unknown request $requestId")
        command(buildJsonObject { put("type", "confirm"); put("callId", callId); put("approved", allowed) })
    }

    /** Allowed: replay the plan as a turn, whose end answers the prompt. Otherwise cancel it and answer now. */
    private fun decidePlan(planId: String, allowed: Boolean, cancelled: Boolean) {
        update(buildJsonObject { put("sessionUpdate", "tool_call_update"); put("toolCallId", "plan-$planId"); put("status", if (allowed) "completed" else "failed") })
        if (allowed) {
            synchronized(lock) { replaying = true }
            return command(buildJsonObject { put("type", "approve_plan"); put("planId", planId); put("id", APPROVE_ID) })
        }
        discard(planId)
        respond(synchronized(lock) { prompt.also { prompt = null } }, buildJsonObject { put("stopReason", if (cancelled) "cancelled" else "end_turn") })
    }

    private fun discard(planId: String) {
        command(buildJsonObject { put("type", "cancel_plan"); put("planId", planId) })
        update(chunk("agent_message_chunk", "Held changes discarded; nothing ran."))
    }

    /** Sends a session/request_permission about [toolCall] with Allow and Reject options; [remember] files its request id. */
    private fun askPermission(toolCall: JsonObject, remember: (String) -> Unit) {
        val requestId = synchronized(lock) { "sophi-${nextId++}".also(remember) }
        send(buildJsonObject {
            put("id", requestId)
            put("method", "session/request_permission")
            putJsonObject("params") {
                put("sessionId", rpc.sessionId)
                put("toolCall", toolCall)
                putJsonArray("options") {
                    addJsonObject { put("optionId", ALLOW); put("name", "Allow"); put("kind", "allow_once") }
                    addJsonObject { put("optionId", REJECT); put("name", "Reject"); put("kind", "reject_once") }
                }
            }
        })
    }

    private fun command(c: JsonObject) { commands.trySend(c.toString()) }

    /** The RPC server's `out`: maps each of its events onto ACP. */
    fun relay(line: String) {
        when (val e = decodeRpcEvent(line)) {
            is RpcEvent.Token -> update(chunk("agent_message_chunk", e.text))
            is RpcEvent.ReasoningToken -> update(chunk("agent_thought_chunk", e.text))
            is RpcEvent.ConfirmationRequested -> e.requests.forEach { r ->
                showTool(r.callId, r.toolName, r.argumentsJson, "pending")
                askPermission(buildJsonObject {
                    put("toolCallId", r.callId)
                    r.preview?.let { putJsonArray("content") { addJsonObject { put("type", "content"); put("content", text(it)) } } }
                }) { asked[it] = r.callId }
            }
            is RpcEvent.PlanReady -> synchronized(lock) { if (prompt != null) held = e }
            is RpcEvent.ToolCallStarted -> showTool(e.callId, e.name, e.argsJson, "in_progress")
            is RpcEvent.ToolCallFinished -> update(buildJsonObject {
                put("sessionUpdate", "tool_call_update")
                put("toolCallId", e.callId)
                put("status", if (e.isError) "failed" else "completed")
                putJsonArray("content") { addJsonObject { put("type", "content"); put("content", text(e.result)) } }
            })
            is RpcEvent.TurnEnded -> {
                var plan: RpcEvent.PlanReady? = null
                var replayed = false
                // Like the REPL, a plan from a turn that stopped early is still asked about; one from a turn the user
                // stopped or that failed is discarded, since nothing the user saw approved it.
                val ask = e.stopReason != "UserStopped" && e.stopReason != "Error"
                val id = synchronized(lock) {
                    plan = held
                    replayed = replaying
                    held = null; replaying = false; shown.clear()
                    prompt.also { if (plan == null || !ask) prompt = null }
                } ?: return
                if (!ask) plan?.let { discard(it.planId) }
                if (e.stopReason == "Error") return fail(id, -32603, e.reply)
                if (replayed && e.stopReason == "Completed") update(chunk("agent_message_chunk", e.reply)) // the replay report
                whyStopped(e.stopReason)?.let { update(chunk("agent_message_chunk", it)) }
                if (ask) plan?.let { return askPlan(it) }
                respond(id, buildJsonObject { put("stopReason", stopReason(e.stopReason)) })
            }
            is RpcEvent.Error -> if (e.id == APPROVE_ID) {
                synchronized(lock) { replaying = false; prompt.also { prompt = null } }?.let { fail(it, -32603, e.message) }
            } else log("acp: ${e.message}")
            else -> {}
        }
    }

    /** Shows the held plan as a pending tool call (so the client has one to attach the ask to) and asks to apply it. */
    private fun askPlan(p: RpcEvent.PlanReady) {
        val call = buildJsonObject {
            put("toolCallId", "plan-${p.planId}")
            put("title", "Apply ${p.steps} held change${if (p.steps == 1) "" else "s"}")
            put("kind", "other")
            put("status", "pending")
            if (p.changes.isNotEmpty()) putJsonArray("content") {
                addJsonObject { put("type", "content"); put("content", text(p.changes.joinToString("\n"))) }
            }
        }
        update(JsonObject(mapOf("sessionUpdate" to JsonPrimitive("tool_call")) + call))
        askPermission(call) { planAsk = it to p.planId }
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

/** Why Sophi stopped early, in plain words, so the editor shows it; null for a normal end, a cancel or an error. */
private fun whyStopped(rpc: String) = when (rpc) {
    "Completed", "UserStopped", "Error", "BudgetExceeded" -> null // the loop already streams its own budget line
    "ToolRoundCeiling" -> "Stopped: the turn used all the tool rounds it is allowed."
    "LoopGuard" -> "Stopped: the same tool calls kept failing."
    "ContextExhausted" -> "Stopped: the conversation no longer fits the model's context window."
    "CompactionThrashing" -> "Stopped: compacting the conversation kept failing to free enough room."
    "EmptyResponse" -> "Stopped: the model returned an empty reply."
    "OutputTruncated" -> "Stopped: the reply hit the model's output token limit."
    "DeniedUnattended" -> "Stopped: too many actions were denied with nobody there to approve them."
    else -> "Stopped: $rpc."
}

/** RPC stop reasons (`Completed` or a TurnStopReason name) as ACP's StopReason. */
private fun stopReason(rpc: String) = when (rpc) {
    "UserStopped" -> "cancelled"
    "OutputTruncated" -> "max_tokens"
    "ToolRoundCeiling" -> "max_turn_requests"
    else -> "end_turn"
}
