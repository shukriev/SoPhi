package dev.sophi.sdk.acp

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanStatus
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.sdk.RuntimeBuilder
import dev.sophi.sdk.rpc.MemoryStatus
import dev.sophi.sdk.rpc.RpcConfirmations
import dev.sophi.sdk.rpc.RpcServer
import dev.sophi.sdk.toStreamFlow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

private fun text(s: String) = LLMResponse.Text(s, TokenUsage(1, 1)).toStreamFlow()
private fun call(id: String, name: String) = LLMResponse.ToolUse(listOf(ToolCall(id, name, "{}")), TokenUsage(1, 0)).toStreamFlow()

private fun streaming(vararg rounds: Flow<StreamEvent>): LLMProvider = mockk<LLMProvider>().also {
    every { it.name } returns "fake"
    var n = 0
    every { it.stream(any()) } answers { rounds[n++] }
}

private val risky = object : Tool {
    override val name = "rm_all"
    override val description = "removes everything"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override suspend fun execute(argumentsJson: String) = "removed"
}

/** An outside change: plan-first holds it as a plan instead of running it. */
private class Outside : Tool {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override val name = "okta__deactivate_user"
    override val description = "deactivates a user"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override fun reach(argumentsJson: String) = Reach.OUTSIDE_CHANGE
    override suspend fun execute(argumentsJson: String) = "deactivated".also { calls += argumentsJson }
}

private val JsonObject.update get() = this["params"]!!.jsonObject["update"]!!.jsonObject
private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

class AcpServerTest : FunSpec({
    /** An ACP server on [llm]; [lines] is everything it wrote (parsed), [input] its stdin. */
    class Rig(llm: LLMProvider, dir: java.nio.file.Path, extra: Tool? = null) {
        val lines: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        lateinit var rpc: RpcServer
        lateinit var acp: AcpServer
        val confirmations = RpcConfirmations { rpc.emit(it) }
        val runtime = RuntimeBuilder().apply {
            provider = llm; sessionsDir = dir; model = "m"; usageLogPath = null
            confirmationPolicy(confirmations)
            tool(risky)
            extra?.let { tool(it) }
            planFirst(PlanFirst.OUTSIDE, dir = dir.resolve("plans"))
        }.contextWindowTokens(100_000).build()
        val input = Channel<String>(Channel.UNLIMITED)
        init {
            rpc = RpcServer(runtime, runtime.sessionManager.create(), confirmations, MemoryStatus(false), { null }, { acp.relay(it) })
            acp = AcpServer(rpc) { lines.add(Json.parseToJsonElement(it).jsonObject) }
        }

        fun snapshot() = synchronized(lines) { lines.toList() }
        suspend fun await(pred: (JsonObject) -> Boolean): JsonObject = withTimeout(10_000) {
            while (true) { snapshot().firstOrNull(pred)?.let { return@withTimeout it }; delay(5) }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        suspend fun response(id: Int) = await { it["id"]?.jsonPrimitive?.content == "$id" && "method" !in it }
        suspend fun send(line: String) = input.send(line)
        fun said() = updates().filter { it.str("sessionUpdate") == "agent_message_chunk" }.joinToString("") { it["content"]!!.jsonObject.str("text")!! }
        fun updates() = snapshot().filter { it.str("method") == "session/update" }.map { it.update }

        /** initialize + session/new; returns the session id. */
        suspend fun open(): String {
            send("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}""")
            send("""{"jsonrpc":"2.0","id":1,"method":"session/new","params":{"cwd":"/tmp","mcpServers":[]}}""")
            return response(1)["result"]!!.jsonObject.str("sessionId")!!
        }
        suspend fun prompt(id: Int, session: String, text: String) =
            send("""{"jsonrpc":"2.0","id":$id,"method":"session/prompt","params":{"sessionId":"$session","prompt":[{"type":"text","text":"$text"}]}}""")
    }

    fun rig(llm: LLMProvider) = Rig(llm, tempdir().toPath())

    test("initialize answers protocol version 1 with capabilities") {
        val r = rig(streaming())
        val job = launch { r.acp.run(r.input) }
        r.send("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}""")
        val res = r.response(0)
        res.str("jsonrpc") shouldBe "2.0"
        val result = res["result"]!!.jsonObject
        result["protocolVersion"]!!.jsonPrimitive.int shouldBe 1
        result["agentCapabilities"]!!.jsonObject.str("loadSession") shouldBe "false"
        result["agentInfo"]!!.jsonObject.str("name") shouldBe "sophi"
        r.input.close()
        job.join()
    }

    test("a prompt streams message chunks and ends with end_turn") {
        val r = rig(streaming(text("hello")))
        val job = launch { r.acp.run(r.input) }
        val sid = r.open()
        r.prompt(2, sid, "hi")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "end_turn"
        val chunk = r.updates().first { it.str("sessionUpdate") == "agent_message_chunk" }
        chunk["content"]!!.jsonObject.str("type") shouldBe "text"
        r.updates().filter { it.str("sessionUpdate") == "agent_message_chunk" }
            .joinToString("") { it["content"]!!.jsonObject.str("text")!! } shouldBe "hello"
        r.snapshot().filter { it.str("method") == "session/update" }
            .forEach { it["params"]!!.jsonObject.str("sessionId") shouldBe sid }
        r.input.close()
        job.join()
    }

    test("a risky tool call asks session/request_permission and runs once allowed") {
        val r = rig(streaming(call("c1", "rm_all"), text("done")))
        val job = launch { r.acp.run(r.input) }
        val sid = r.open()
        r.prompt(2, sid, "wipe")
        val ask = r.await { it.str("method") == "session/request_permission" }
        val params = ask["params"]!!.jsonObject
        params.str("sessionId") shouldBe sid
        params["toolCall"]!!.jsonObject.str("toolCallId") shouldBe "c1"
        params["options"]!!.jsonArray.map { it.jsonObject.str("kind") } shouldBe listOf("allow_once", "reject_once")
        val allow = params["options"]!!.jsonArray.first { it.jsonObject.str("kind") == "allow_once" }.jsonObject.str("optionId")
        r.send("""{"jsonrpc":"2.0","id":${ask["id"]},"result":{"outcome":{"outcome":"selected","optionId":"$allow"}}}""")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "end_turn"
        val tool = r.updates().filter { it.str("toolCallId") == "c1" }
        tool.first().str("sessionUpdate") shouldBe "tool_call"
        tool.first().str("title") shouldContain "rm_all"
        tool.mapNotNull { it.str("status") } shouldContainInOrder listOf("pending", "in_progress", "completed")
        tool.last()["content"].toString() shouldContain "removed"
        r.input.close()
        job.join()
    }

    test("a rejected permission fails the tool call") {
        val r = rig(streaming(call("c1", "rm_all"), text("ok")))
        val job = launch { r.acp.run(r.input) }
        val sid = r.open()
        r.prompt(2, sid, "wipe")
        val ask = r.await { it.str("method") == "session/request_permission" }
        r.send("""{"jsonrpc":"2.0","id":${ask["id"]},"result":{"outcome":{"outcome":"cancelled"}}}""")
        r.response(2)
        r.updates().last { it.str("toolCallId") == "c1" }.str("status") shouldBe "failed"
        r.input.close()
        job.join()
    }

    test("session/cancel stops the turn with stopReason cancelled") {
        val started = CompletableDeferred<Unit>()
        val r = rig(streaming(flow { emit(StreamEvent.Content("part")); started.complete(Unit); awaitCancellation() }))
        val job = launch { r.acp.run(r.input) }
        val sid = r.open()
        r.prompt(2, sid, "go")
        started.await()
        r.send("""{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"$sid"}}""")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "cancelled"
        r.input.close()
        job.join()
    }

    test("bad requests get JSON-RPC errors") {
        val r = rig(streaming())
        val job = launch { r.acp.run(r.input) }
        r.open()
        r.send("""{"jsonrpc":"2.0","id":5,"method":"session/dance","params":{}}""")
        r.response(5)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int shouldBe -32601
        r.prompt(6, "nope", "hi")
        r.response(6)["error"] shouldNotBe null
        r.send("not json")
        r.await { it["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int == -32700 }
        r.input.close()
        job.join()
    }

    test("a second session/new starts a fresh session") {
        val r = rig(streaming())
        val job = launch { r.acp.run(r.input) }
        val first = r.open()
        r.send("""{"jsonrpc":"2.0","id":3,"method":"session/new","params":{"cwd":"/tmp","mcpServers":[]}}""")
        r.response(3)["result"]!!.jsonObject.str("sessionId") shouldNotBe first
        r.input.close()
        job.join()
    }

    /** A prompt whose turn held one outside change as a plan; returns the rig, its job, the session and the plan ask. */
    suspend fun kotlinx.coroutines.CoroutineScope.held(okta: Outside): Triple<Rig, kotlinx.coroutines.Job, JsonObject> {
        val r = Rig(streaming(call("c1", okta.name), text("I plan to deactivate Jane.")), tempdir().toPath(), okta)
        val job = launch { r.acp.run(r.input) }
        r.prompt(2, r.open(), "offboard jane")
        val ask = r.await { it.str("method") == "session/request_permission" }
        return Triple(r, job, ask)
    }

    test("a turn that held changes as a plan asks to apply them, and the prompt waits for the answer") {
        val okta = Outside()
        val (r, job, ask) = held(okta)
        val call = ask["params"]!!.jsonObject["toolCall"]!!.jsonObject
        call.str("title") shouldBe "Apply 1 held change"
        call["content"].toString() shouldContain okta.name
        r.updates().any { it.str("toolCallId") == call.str("toolCallId") && it.str("sessionUpdate") == "tool_call" } shouldBe true
        r.snapshot().none { it["id"]?.jsonPrimitive?.content == "2" && "method" !in it } shouldBe true
        okta.calls shouldBe emptyList()
        r.input.close()
        job.join()
    }

    test("allowing a held plan replays it and answers the prompt with the replay report") {
        val okta = Outside()
        val (r, job, ask) = held(okta)
        r.send("""{"jsonrpc":"2.0","id":${ask["id"]},"result":{"outcome":{"outcome":"selected","optionId":"allow"}}}""")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "end_turn"
        okta.calls.size shouldBe 1
        r.said() shouldContain "1 ok"
        r.runtime.plans().single().status shouldBe PlanStatus.DONE
        r.input.close()
        job.join()
    }

    test("rejecting a held plan discards it; nothing runs") {
        val okta = Outside()
        val (r, job, ask) = held(okta)
        r.send("""{"jsonrpc":"2.0","id":${ask["id"]},"result":{"outcome":{"outcome":"selected","optionId":"reject"}}}""")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "end_turn"
        r.said() shouldContain "Held changes discarded; nothing ran."
        r.input.close()
        job.join()
        okta.calls shouldBe emptyList()
        r.runtime.plans().single().status shouldBe PlanStatus.CANCELLED
    }

    test("session/cancel while the plan ask is pending discards the plan and answers cancelled") {
        val okta = Outside()
        val (r, job, _) = held(okta)
        r.send("""{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"${r.rpc.sessionId}"}}""")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "cancelled"
        r.said() shouldContain "Held changes discarded; nothing ran."
        r.input.close()
        job.join()
        okta.calls shouldBe emptyList()
        r.runtime.plans().single().status shouldBe PlanStatus.CANCELLED
    }

    test("an early stop says why before the prompt is answered") {
        val started = CompletableDeferred<Unit>()
        val r = rig(streaming(flow { started.complete(Unit); awaitCancellation() }))
        val job = launch { r.acp.run(r.input) }
        r.prompt(2, r.open(), "go")
        started.await() // the prompt is registered; stand in for the turn ending early
        r.acp.relay("""{"type":"turn_ended","reply":"","stopReason":"LoopGuard"}""")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "end_turn"
        r.said() shouldBe "Stopped: the same tool calls kept failing."
        job.cancel()
    }

    test("a turn that stops early still asks about the plan it held, as the REPL does") {
        val okta = Outside()
        val r = Rig(streaming(call("c1", okta.name), text("")), tempdir().toPath(), okta)
        val job = launch { r.acp.run(r.input) }
        r.prompt(2, r.open(), "offboard jane")
        val ask = r.await { it.str("method") == "session/request_permission" }
        r.said() shouldContain "Stopped:"
        r.send("""{"jsonrpc":"2.0","id":${ask["id"]},"result":{"outcome":{"outcome":"selected","optionId":"reject"}}}""")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "end_turn"
        r.input.close()
        job.join()
        r.runtime.plans().single().status shouldBe PlanStatus.CANCELLED
    }

    test("a plan held by a turn the user stopped is discarded, and the prompt answers cancelled") {
        val started = CompletableDeferred<Unit>()
        val r = rig(streaming(flow { started.complete(Unit); awaitCancellation() }))
        val job = launch { r.acp.run(r.input) }
        r.prompt(2, r.open(), "go")
        // Stand in for a turn that held a plan and then ended without completing (cancel_plan goes the reject path's way).
        started.await()
        r.acp.relay("""{"type":"plan_ready","planId":"p1","steps":1}""")
        r.acp.relay("""{"type":"turn_ended","reply":"","stopReason":"UserStopped"}""")
        r.response(2)["result"]!!.jsonObject.str("stopReason") shouldBe "cancelled"
        r.said() shouldContain "Held changes discarded; nothing ran."
        r.snapshot().none { it.str("method") == "session/request_permission" } shouldBe true
        job.cancel()
    }
})
