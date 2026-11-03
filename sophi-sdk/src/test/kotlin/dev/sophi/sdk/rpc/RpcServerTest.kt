package dev.sophi.sdk.rpc

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.sdk.RuntimeBuilder
import dev.sophi.sdk.toStreamFlow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

private fun text(s: String) = LLMResponse.Text(s, TokenUsage(1, 1)).toStreamFlow()
private fun call(id: String, name: String) = LLMResponse.ToolUse(listOf(ToolCall(id, name, "{}")), TokenUsage(1, 0)).toStreamFlow()

/** One model request per round, in order. */
private fun streaming(vararg rounds: Flow<StreamEvent>): LLMProvider = mockk<LLMProvider>().also {
    every { it.name } returns "fake"
    var n = 0
    every { it.stream(any()) } answers { rounds[n++] }
}

/** Needs a person's yes every time. */
private val risky = object : Tool {
    override val name = "rm_all"
    override val description = "removes everything"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override suspend fun execute(argumentsJson: String) = "removed"
}

private fun type(line: String) = Json.parseToJsonElement(line).jsonObject["type"]!!.jsonPrimitive.content

class RpcServerTest : FunSpec({
    /** A server on [llm]; [lines] is everything it wrote, [input] its stdin. */
    class Rig(llm: LLMProvider, dir: java.nio.file.Path) {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        lateinit var server: RpcServer
        val confirmations = RpcConfirmations { server.emit(it) }
        val runtime = RuntimeBuilder().apply {
            provider = llm; sessionsDir = dir; model = "m"; usageLogPath = null
            confirmationPolicy(confirmations)
            tool(risky)
        }.contextWindowTokens(100_000).build()
        val session: AgentSession = runtime.sessionManager.create()
        val input = Channel<String>(Channel.UNLIMITED)
        init { server = RpcServer(runtime, session, confirmations, MemoryStatus(false), { null }, { lines.add(it) }) }

        fun types() = synchronized(lines) { lines.map(::type) }
        fun last(t: String) = synchronized(lines) { lines.last { type(it) == t } }
        suspend fun awaitType(t: String, count: Int = 1) = withTimeout(10_000) { while (types().count { it == t } < count) delay(5) }
        suspend fun send(line: String) = input.send(line)
    }

    fun rig(llm: LLMProvider) = Rig(llm, tempdir().toPath())

    test("ready then a prompt yields tokens and turn_ended") {
        val r = rig(streaming(text("hello")))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"hi"}""")
        r.input.close()
        job.join()
        val types = r.types()
        types.take(2) shouldBe listOf("ready", "turn_started")
        types shouldContain "token"
        types.takeLast(2) shouldBe listOf("turn_ended", "exited")
        r.last("turn_ended") shouldContain "\"stopReason\":\"Completed\""
        r.last("turn_ended") shouldContain "\"reply\":\"hello\""
    }

    test("every line is one JSON object with a type") {
        val r = rig(streaming(call("c1", "rm_all"), text("done \"quoted\"\nnext line ü")))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"hi"}""")
        r.send("not json")
        r.awaitType("confirmation_requested")
        r.send("""{"type":"confirm","callId":"c1","approved":true}""")
        r.awaitType("turn_ended")
        r.send("""{"type":"branch"}""")
        r.input.close()
        job.join()
        r.lines.forEach { line ->
            line shouldBe line.trim()
            ('\n' in line) shouldBe false
            Json.parseToJsonElement(line).jsonObject.containsKey("type") shouldBe true
        }
    }

    test("confirmation waits for confirm") {
        val r = rig(streaming(call("c1", "rm_all"), text("done")))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"hi"}""")
        r.awaitType("confirmation_requested")
        delay(200)
        r.types().last() shouldBe "confirmation_requested"
        r.last("confirmation_requested") shouldContain "\"toolName\":\"rm_all\""
        r.send("""{"type":"confirm","callId":"c1","approved":false}""")
        r.awaitType("turn_ended")
        r.last("confirmation_resolved") shouldContain "\"approved\":false"
        r.last("tool_call_finished") shouldContain "denied"
        r.input.close()
        job.join()
    }

    test("stdin EOF denies a pending confirmation") {
        val r = rig(streaming(call("c1", "rm_all"), text("done")))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"hi"}""")
        r.awaitType("confirmation_requested")
        r.input.close()
        job.join()
        r.last("confirmation_resolved") shouldContain "\"approved\":false"
        r.last("tool_call_finished") shouldContain "denied"
        r.types().takeLast(2) shouldBe listOf("turn_ended", "exited")
    }

    test("garbage mid-turn is an error and the turn goes on") {
        val gate = CompletableDeferred<Unit>()
        val r = rig(streaming(flow { gate.await(); emit(StreamEvent.Content("ok")) }))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"hi"}""")
        r.awaitType("turn_started")
        r.send("not json")
        r.send("""{"type":"dance"}""")
        r.awaitType("error", 2)
        gate.complete(Unit)
        r.awaitType("turn_ended")
        r.input.close()
        job.join()
        r.types().count { it == "error" } shouldBe 2
        r.last("turn_ended") shouldContain "\"stopReason\":\"Completed\""
    }

    test("session commands are refused while a turn runs") {
        val gate = CompletableDeferred<Unit>()
        val r = rig(streaming(flow { gate.await(); emit(StreamEvent.Content("ok")) }))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"hi"}""")
        r.awaitType("turn_started")
        r.send("""{"type":"compact","id":"k1"}""")
        r.awaitType("error")
        r.last("error") shouldContain "a turn is running"
        r.last("error") shouldContain "\"id\":\"k1\""
        gate.complete(Unit)
        r.input.close()
        job.join()
    }

    test("unknown callId is an error") {
        val r = rig(streaming(call("c1", "rm_all"), text("done")))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"hi"}""")
        r.awaitType("confirmation_requested")
        r.send("""{"type":"confirm","callId":"nope","approved":true,"id":"x"}""")
        r.awaitType("error")
        r.last("error") shouldContain "\"id\":\"x\""
        r.send("""{"type":"confirm","callId":"c1","approved":true}""")
        r.awaitType("turn_ended")
        r.last("tool_call_finished") shouldContain "removed"
        r.input.close()
        job.join()
    }

    test("abort stops the turn; abort when idle is an error") {
        val started = CompletableDeferred<Unit>()
        val r = rig(streaming(flow { emit(StreamEvent.Content("part")); started.complete(Unit); awaitCancellation() }))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"abort","id":"a0"}""")
        r.awaitType("error")
        r.last("error") shouldContain "\"id\":\"a0\""
        r.send("""{"type":"prompt","text":"hi"}""")
        started.await()
        r.send("""{"type":"abort"}""")
        r.awaitType("turn_ended")
        r.last("turn_ended") shouldContain "\"stopReason\":\"UserStopped\""
        r.last("turn_ended") shouldContain "\"reply\":\"part\""
        r.input.close()
        job.join()
        r.types().last() shouldBe "exited"
    }

    test("abort during a goal ends Interrupted") {
        val stepRunning = CompletableDeferred<Unit>()
        val llm = mockk<LLMProvider>().also {
            every { it.name } returns "fake"
            coEvery { it.complete(any()) } returns LLMResponse.Text("""{"steps":[{"id":"step_1","instruction":"do x"}]}""", TokenUsage(1, 1))
            every { it.stream(any()) } returns flow { stepRunning.complete(Unit); awaitCancellation() }
        }
        val r = rig(llm)
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"goal","text":"do x"}""")
        stepRunning.await()
        r.send("""{"type":"abort"}""")
        r.awaitType("turn_ended")
        r.last("turn_ended") shouldContain "\"stopReason\":\"UserStopped\""
        r.last("turn_ended") shouldContain "\"goalStatus\":\"Interrupted\""
        r.input.close()
        job.join()
    }

    test("prompt during a turn is queued as a follow-up") {
        val gate = CompletableDeferred<Unit>()
        val r = rig(streaming(flow { gate.await(); emit(StreamEvent.Content("A")) }, text("B")))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"first"}""")
        r.awaitType("turn_started")
        r.send("""{"type":"prompt","text":"second"}""")
        delay(100)
        gate.complete(Unit)
        r.input.close()
        job.join()
        r.types() shouldContain "follow_up_started"
        r.types().count { it == "turn_ended" } shouldBe 1
        r.last("turn_ended") shouldContain "\"reply\":\"B\""
        val users = r.runtime.sessionManager.load(r.session.id).entries.filter { it.role == EntryRole.USER }.map { it.content }
        users shouldContainAll listOf("first", "second")
    }

    test("abort then a prompt at once runs the prompt") {
        val started = CompletableDeferred<Unit>()
        val r = rig(streaming(flow { started.complete(Unit); awaitCancellation() }, text("again")))
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"first"}""")
        started.await()
        r.send("""{"type":"abort"}""")
        r.send("""{"type":"prompt","text":"second"}""")
        r.input.close()
        job.join()
        r.types().count { it == "turn_started" } shouldBe 2
        r.types().count { it == "turn_ended" } shouldBe 2
        r.last("turn_ended") shouldContain "\"reply\":\"again\""
    }

    // The race is between the reader seeing a busy turn and the turn going idle; threads make it show.
    test("a prompt sent as the turn ends is never lost") {
        repeat(40) {
            val r = rig(streaming(text("A"), text("B")))
            val job = launch(Dispatchers.Default) { r.server.run(r.input) }
            r.send("""{"type":"prompt","text":"first"}""")
            r.awaitType("token")
            r.send("""{"type":"prompt","text":"second"}""")
            r.input.close()
            withTimeout(10_000) { job.join() }
            val users = r.runtime.sessionManager.load(r.session.id).entries.filter { it.role == EntryRole.USER }.map { it.content }
            users shouldBe listOf("first", "second")
            r.types().last() shouldBe "exited"
        }
    }

    test("goal ends with goalStatus") {
        val llm = mockk<LLMProvider>().also {
            every { it.name } returns "fake"
            coEvery { it.complete(any()) } returnsMany listOf("""{"steps":[{"id":"step_1","instruction":"do x"}]}""", "1.0", "YES")
                .map { t -> LLMResponse.Text(t, TokenUsage(1, 1)) }
            every { it.stream(any()) } returns flowOf(StreamEvent.Content("x done"))
        }
        val r = rig(llm)
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"goal","text":"do x"}""")
        r.input.close()
        job.join()
        r.last("turn_ended") shouldContain "\"goalStatus\":\"Met\""
        r.last("turn_ended") shouldContain "\"stopReason\":\"Completed\""
        r.types().last() shouldBe "exited"
    }

    test("branch acks with the entries") {
        val r = rig(streaming())
        r.session.append(EntryRole.USER, "hello there")
        r.session.append(EntryRole.ASSISTANT, "hi back")
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"branch","id":"b1"}""")
        r.input.close()
        job.join()
        val ack = r.last("ack")
        ack shouldContain "\"id\":\"b1\""
        ack shouldContain "\"command\":\"branch\""
        ack shouldContain "1. [USER] ${r.session.branch()[0].id}  hello there"
        ack shouldContain "2. [ASSISTANT]"
    }

    test("set_model acks, or errors with the switcher's text") {
        val r = rig(streaming())
        r.server = RpcServer(r.runtime, r.session, r.confirmations, MemoryStatus(false),
            { m -> if (m == "bad") "unknown model bad" else null }, { r.lines.add(it) })
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"set_model","model":"good","id":"m1"}""")
        r.send("""{"type":"set_model","model":"bad","id":"m2"}""")
        r.input.close()
        job.join()
        r.last("ack") shouldContain "\"id\":\"m1\""
        r.last("error") shouldContain "unknown model bad"
    }
})
