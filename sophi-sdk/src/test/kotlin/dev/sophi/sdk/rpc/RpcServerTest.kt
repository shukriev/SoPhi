package dev.sophi.sdk.rpc

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.credentials.SecretMask
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanStatus
import dev.sophi.core.tools.Tool
import dev.sophi.sdk.RuntimeBuilder
import dev.sophi.sdk.SophiRuntime
import dev.sophi.sdk.toStreamFlow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldNotContain
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
    class Rig(llm: LLMProvider, dir: java.nio.file.Path, extra: Tool? = null, plugin: dev.sophi.extensions.SophiPlugin? = null) {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        lateinit var server: RpcServer
        val confirmations = RpcConfirmations { server.emit(it) }
        val runtime = RuntimeBuilder().apply {
            provider = llm; sessionsDir = dir; model = "m"; usageLogPath = null
            confirmationPolicy(confirmations)
            tool(risky)
            extra?.let { tool(it) }
            plugin?.let { plugin(it) }
            planFirst(PlanFirst.OUTSIDE, dir = dir.resolve("plans"))
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

    /** An outside change: plan-first records it instead of running it. */
    class Outside : Tool {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override val name = "okta__deactivate_user"
        override val description = "deactivates a user"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
        override fun reach(argumentsJson: String) = Reach.OUTSIDE_CHANGE
        override suspend fun execute(argumentsJson: String) = "deactivated".also { calls += argumentsJson }
    }

    /** A running server whose first turn left one pending plan; returns its rig, job and plan id. */
    suspend fun kotlinx.coroutines.CoroutineScope.planned(okta: Outside): Triple<Rig, kotlinx.coroutines.Job, String> {
        val r = Rig(streaming(call("c1", okta.name), text("I plan to deactivate Jane.")), tempdir().toPath(), okta)
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"offboard jane"}""")
        r.awaitType("turn_ended")
        return Triple(r, job, r.runtime.plans().single().id)
    }

    fun field(line: String, key: String) = Json.parseToJsonElement(line).jsonObject[key]!!.jsonPrimitive.content

    test("plans lists the pending plan; nothing ran") {
        val okta = Outside()
        val (r, job, id) = planned(okta)
        r.send("""{"type":"plans","id":"1"}""")
        r.awaitType("ack")
        field(r.last("ack"), "command") shouldBe "plans"
        field(r.last("ack"), "result") shouldContain "$id  pending"
        okta.calls shouldBe emptyList()
        r.input.close(); job.join()
    }

    test("approve_plan approves and replays the steps as a turn whose reply is the replay report") {
        val okta = Outside()
        val (r, job, id) = planned(okta)
        r.send("""{"type":"approve_plan","planId":"$id","id":"7"}""")
        r.awaitType("turn_ended", 2)
        field(r.last("turn_started"), "input") shouldBe "approve_plan $id"
        field(r.last("turn_ended"), "reply") shouldBe "Plan $id: 1 ok"
        field(r.last("turn_ended"), "stopReason") shouldBe "Completed"
        okta.calls.size shouldBe 1
        r.runtime.plan(id)!!.status shouldBe PlanStatus.DONE
        r.input.close(); job.join()
        // The outcome note lands on the live session, so the next turn sees it.
        r.runtime.sessionManager.load(r.server.sessionId).entries.last().metadata["plan"] shouldBe id
    }

    test("abort during a plan replay stops it; the steps not run are recorded, other commands get a turn is running") {
        val started = CompletableDeferred<Unit>()
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val slow = object : Tool {
            override val name = "okta__deactivate_user"
            override val description = "deactivates a user"
            override val parametersJson = """{"type":"object","properties":{}}"""
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override fun reach(argumentsJson: String) = Reach.OUTSIDE_CHANGE
            override suspend fun execute(argumentsJson: String): String { ran += argumentsJson; started.complete(Unit); awaitCancellation() }
        }
        fun deactivate(id: String, user: String) =
            LLMResponse.ToolUse(listOf(ToolCall(id, slow.name, """{"user":"$user"}""")), TokenUsage(1, 0)).toStreamFlow()
        val r = Rig(streaming(deactivate("c1", "jane"), deactivate("c2", "joe"), text("I plan two.")), tempdir().toPath(), slow)
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"offboard two"}""")
        r.awaitType("turn_ended")
        val id = r.runtime.plans().single().id
        r.runtime.plan(id)!!.steps.size shouldBe 2
        r.send("""{"type":"approve_plan","planId":"$id","id":"7"}""")
        started.await()
        r.send("""{"type":"plans","id":"p"}""")
        r.awaitType("error")
        r.last("error") shouldContain "a turn is running"
        r.send("""{"type":"abort","id":"a"}""")
        r.awaitType("turn_ended", 2)
        r.last("turn_ended") shouldContain "\"stopReason\":\"UserStopped\""
        field(r.last("turn_ended"), "reply") shouldBe "Plan $id: 1 stopped mid-call (it may have run) · 2 not run"
        r.types().count { it == "error" } shouldBe 1 // the abort found the replay running
        val plan = r.runtime.plan(id)!!
        plan.status shouldBe PlanStatus.CANCELLED
        plan.results.map { it.outcome } shouldBe listOf("cancelled", "not-run")
        ran.size shouldBe 1
        r.input.close(); job.join()
    }

    // Another writer holds the output, so announcing the replay suspends; the abort, read in the same pass, lands there.
    test("abort before the replay starts cancels the plan instead of leaving it approved") {
        val okta = Outside()
        val (r, job, id) = planned(okta)
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val r2 = RpcServer(r.runtime, r.session, r.confirmations, MemoryStatus(false), { null }, out = { line: String ->
            if ("hold" in line) { held.countDown(); release.await() }
            r.lines.add(line)
        })
        r.input.close(); job.join()
        val input = Channel<String>(Channel.UNLIMITED)
        val run = launch { r2.run(input) }
        r.awaitType("ready", 2)
        val holder = launch(Dispatchers.IO) { r2.emit(RpcEvent.Error("hold")) }
        held.await()
        input.send("""{"type":"approve_plan","planId":"$id"}""")
        input.send("""{"type":"abort"}""")
        delay(200)
        release.countDown()
        holder.join(); input.close(); withTimeout(10_000) { run.join() }
        r.last("turn_ended") shouldContain "\"stopReason\":\"UserStopped\""
        r.runtime.plan(id)!!.status shouldBe PlanStatus.CANCELLED
        okta.calls shouldBe emptyList()
    }

    test("approve_plan on a plan that isn't pending, or doesn't exist, is an error and runs nothing") {
        val okta = Outside()
        val (r, job, id) = planned(okta)
        r.runtime.cancelPlan(id)
        r.send("""{"type":"approve_plan","planId":"$id","id":"a"}""")
        r.send("""{"type":"approve_plan","planId":"nope","id":"b"}""")
        r.awaitType("error", 2)
        val errors = synchronized(r.lines) { r.lines.filter { type(it) == "error" } }
        errors[0] shouldContain "Only a pending plan can be approved; $id is cancelled"
        errors[1] shouldContain "No plan nope"
        okta.calls shouldBe emptyList()
        r.input.close(); job.join()
    }

    test("cancel_plan cancels a pending plan; it can't be approved after") {
        val okta = Outside()
        val (r, job, id) = planned(okta)
        r.send("""{"type":"cancel_plan","planId":"$id"}""")
        r.awaitType("ack")
        field(r.last("ack"), "command") shouldBe "cancel_plan"
        r.runtime.plan(id)!!.status shouldBe PlanStatus.CANCELLED
        r.send("""{"type":"approve_plan","planId":"$id"}""")
        r.awaitType("error")
        okta.calls shouldBe emptyList()
        r.input.close(); job.join()
    }

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

    // All three lines are read before the turn's body gets the thread, so the abort lands before it starts.
    test("abort before the turn starts still ends it, and the next prompt runs") {
        val r = rig(streaming(text("again")))
        r.send("""{"type":"prompt","text":"first"}""")
        r.send("""{"type":"abort"}""")
        r.send("""{"type":"prompt","text":"second"}""")
        r.input.close()
        withTimeout(10_000) { r.server.run(r.input) }
        r.types().count { it == "turn_ended" } shouldBe 2
        r.lines.first { type(it) == "turn_ended" } shouldContain "\"stopReason\":\"UserStopped\""
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

    // A client that answers turn_ended at once, on another thread; the sleep holds the turn's thread
    // just after the write, so the reader handles the command before the turn could go idle late.
    test("a session command sent right after turn_ended is acked") {
        repeat(20) {
            val r = rig(streaming(text("A")))
            r.server = RpcServer(r.runtime, r.session, r.confirmations, MemoryStatus(false), { null }, { line ->
                r.lines.add(line)
                if (type(line) == "turn_ended") {
                    r.input.trySend("""{"type":"branch","id":"b"}""")
                    r.input.close()
                    Thread.sleep(50)
                }
            })
            val job = launch(Dispatchers.Default) { r.server.run(r.input) }
            r.send("""{"type":"prompt","text":"first"}""")
            withTimeout(10_000) { job.join() }
            r.types() shouldNotContain "error"
            r.types().takeLast(2) shouldBe listOf("ack", "exited")
        }
    }

    test("confirmation_requested masks stored secrets") {
        val mask = SecretMask().apply { add("sk-supersecret") }
        val runtime = mockk<SophiRuntime> { every { maskSecrets(any()) } answers { mask.mask(firstArg()) } }
        val lines = mutableListOf<String>()
        val server = RpcServer(runtime, mockk(), RpcConfirmations {}, MemoryStatus(false), { null }, { lines.add(it) })
        server.emit(RpcEvent.ConfirmationRequested(listOf(ConfirmationRequest(
            "c1", "http", """{"token":"sk-supersecret"}""", RiskLevel.DESTRUCTIVE, preview = "curl -H sk-supersecret"))))
        lines.single() shouldNotContain "sk-supersecret"
        lines.single() shouldContain "[REDACTED:secret]"
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

    test("newSession ends the session it replaces once, in the background, before exited; an empty one gets nothing") {
        val ended: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val spy = object : dev.sophi.extensions.SophiPlugin {
            override val name = "spy"
            override fun hooks() = listOf(dev.sophi.extensions.HookPoint.ON_SESSION_START, dev.sophi.extensions.HookPoint.ON_SESSION_END).map { p ->
                object : dev.sophi.extensions.AgentHook {
                    override val point = p
                    override suspend fun invoke(context: dev.sophi.extensions.HookContext) {
                        log += "$p ${context.sessionId}"
                        if (p == dev.sophi.extensions.HookPoint.ON_SESSION_END) ended += context.sessionId
                    }
                }
            }
        }
        val r = Rig(streaming(text("hi")), tempdir().toPath(), plugin = spy)
        val job = launch { r.server.run(r.input) }
        r.send("""{"type":"prompt","text":"hello"}""")
        r.awaitType("turn_ended")
        val first = r.server.sessionId
        val second = r.server.newSession()!!
        val third = r.server.newSession()!!
        third shouldNotBe second // the empty second is replaced without any end work
        r.input.close(); job.join()
        ended shouldBe listOf(first) // the current (third) session is the host's to end at exit
        // Each new session gets SophiRuntime.newSession's start work, once, after the old one's end.
        log shouldBe listOf("ON_SESSION_END $first", "ON_SESSION_START $second", "ON_SESSION_START $third")
        r.runtime.sessionManager.load(second).id shouldBe second // saved, so it can be resumed
        r.types().last() shouldBe "exited"
    }

    test("a new session's first prompt waits for its start hook, and commands are still read meanwhile") {
        val gate = CompletableDeferred<Unit>()
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val slow = object : dev.sophi.extensions.SophiPlugin {
            override val name = "slow"
            override fun hooks() = listOf(object : dev.sophi.extensions.AgentHook {
                override val point = dev.sophi.extensions.HookPoint.ON_SESSION_START
                override suspend fun invoke(context: dev.sophi.extensions.HookContext) { gate.await(); log += "start" }
            })
        }
        val llm = mockk<LLMProvider>().also {
            every { it.name } returns "fake"
            every { it.stream(any()) } answers { log += "turn"; text("hi") }
        }
        val r = Rig(llm, tempdir().toPath(), plugin = slow)
        val job = launch { r.server.run(r.input) }
        r.server.newSession()!!
        r.send("""{"type":"prompt","text":"hello"}""")
        r.send("""{"type":"plans","id":"p"}""")
        r.awaitType("error") // the reader is not blocked: it answers while the prompt waits
        r.last("error") shouldContain "a turn is running"
        log shouldBe emptyList()
        gate.complete(Unit)
        r.awaitType("turn_ended")
        log shouldBe listOf("start", "turn")
        r.input.close(); job.join()
    }
})
