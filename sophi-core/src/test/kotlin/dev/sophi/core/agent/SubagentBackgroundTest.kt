package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanRecorder
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRule
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.permissions.PermissionScope
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.io.path.createTempDirectory

private class Marker : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Marker> }

class SubagentBackgroundTest : FunSpec({

    val explore = AgentDefinition("explore", "Read-only search", "You search code.", allowedTools = listOf("read_file"))
    val review = AgentDefinition("review", "Reviews", "You review.", allowedTools = listOf("read_file"))
    val operator = AgentDefinition("operator", "Acts", "You act.", allowedTools = listOf("danger"))
    val readTool = object : Tool {
        override val name = "read_file"
        override val description = "Reads a file"
        override val parametersJson = "{}"
        override suspend fun execute(argumentsJson: String) = "file contents"
    }
    var executed = false
    val danger = object : Tool {
        override val name = "danger"
        override val description = "risky"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = RiskLevel.CAUTION
        override suspend fun execute(argumentsJson: String): String { executed = true; return "did it" }
    }
    beforeEach { executed = false }

    val requests = Collections.synchronizedList(mutableListOf<CompletionRequest>())
    beforeEach { requests.clear() }

    /** Answers [text], after [gate] when given. */
    fun textProvider(text: String = "the answer", gate: CompletableDeferred<Unit>? = null) = mockk<LLMProvider>().also { p ->
        every { p.stream(any()) } answers {
            requests += firstArg<CompletionRequest>()
            flow { gate?.await(); emitAll(LLMResponse.Text(text, TokenUsage(1, 1)).toStreamFlow()) }
        }
    }

    /** Calls `danger` once, then answers. */
    fun dangerProvider() = mockk<LLMProvider>().also { p ->
        var n = 0
        every { p.stream(any()) } answers {
            requests += firstArg<CompletionRequest>()
            if (++n == 1) LLMResponse.ToolUse(listOf(ToolCall("c1", "danger", "{}")), TokenUsage(1, 0)).toStreamFlow()
            else LLMResponse.Text("gave up", TokenUsage(1, 1)).toStreamFlow()
        }
    }

    val sessionsDir = createTempDirectory("subagent-bg")
    fun tool(
        provider: LLMProvider, bg: BackgroundSubagents? = BackgroundSubagents(),
        policy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
        backgroundPolicy: ConfirmationPolicy = ConfirmationPolicy.DENY_ALL, depth: Int = 0,
    ) = SubagentTool(
        definitions = listOf(explore, review, operator), provider = provider,
        fullRegistry = ToolRegistry().register(readTool).register(danger),
        sessionManager = FileSessionManager(sessionsDir), parentConfig = AgentConfig(model = "m"),
        contextWindowTokens = 100_000, depth = depth, confirmationPolicy = policy,
        background = bg, backgroundPolicy = backgroundPolicy,
    )

    suspend fun <T> inSession(id: String = "parent-1", block: suspend () -> T) = withContext(SessionIdContext(id)) { block() }
    fun sessionOf(answer: String) = Regex("""resume="([^"]+)"""").find(answer)!!.groupValues[1]

    test("background returns the start line before the subagent finishes") {
        val bg = BackgroundSubagents()
        val gate = CompletableDeferred<Unit>()
        val reply = inSession { tool(textProvider(gate = gate), bg).execute("""{"subagent_type":"explore","prompt":"look","background":true}""") }
        reply shouldBe """Started subagent b1 (explore). Keep working; read its result with subagent_result("b1") — wait_seconds waits for it."""
        bg.list().single().status shouldBe BackgroundSubagents.Status.RUNNING
        gate.complete(Unit)
        bg.close()
    }

    test("subagent_result returns the answer with the resume footer") {
        val bg = BackgroundSubagents()
        inSession { tool(textProvider(), bg).execute("""{"subagent_type":"explore","prompt":"look","background":true}""") }
        val out = inSession { SubagentResultTool(bg).execute("""{"id":"b1","wait_seconds":5}""") }
        out shouldStartWith "done\nthe answer"
        out shouldEndWith "pass resume=\"${bg.list().single().subSessionId}\" to continue it)"
        inSession("other") { SubagentResultTool(bg).execute("""{"id":"b1"}""") } shouldBe "Error: no such subagent b1"
        bg.close()
    }

    test("subagent_cancel stops one") {
        val bg = BackgroundSubagents()
        inSession { tool(textProvider(gate = CompletableDeferred()), bg).execute("""{"subagent_type":"explore","prompt":"look","background":true}""") }
        inSession { SubagentCancelTool(bg).execute("""{"id":"b1"}""") } shouldBe "cancelled b1"
        inSession { SubagentResultTool(bg).execute("""{"id":"b1","wait_seconds":5}""") } shouldBe "cancelled"
        bg.close()
    }

    test("background ask is denied, never prompted") {
        val bg = BackgroundSubagents()
        val parentPolicy = mockk<ConfirmationPolicy>() // any call fails: nobody may be prompted
        inSession { tool(dangerProvider(), bg, policy = parentPolicy).execute("""{"subagent_type":"operator","prompt":"act","background":true}""") }
        bg.result(null, "b1", 5)!!.text!! shouldContain "gave up"
        executed shouldBe false
        requests.last().messages.toString() shouldContain "nobody is here to approve"
        requests.first().systemPrompt!! shouldContain "nobody can approve your actions"
        bg.close()
    }

    test("backgroundPolicy ALLOW_ALL runs it") {
        val bg = BackgroundSubagents()
        inSession { tool(dangerProvider(), bg, backgroundPolicy = ConfirmationPolicy.ALLOW_ALL).execute("""{"subagent_type":"operator","prompt":"act","background":true}""") }
        bg.result(null, "b1", 5)!!.status shouldBe BackgroundSubagents.Status.DONE
        executed shouldBe true
        bg.close()
    }

    test("foreground answer ends with the resume footer") {
        val out = inSession { tool(textProvider()).execute("""{"subagent_type":"explore","prompt":"look"}""") }
        out shouldStartWith "the answer\n\n(subagent session "
        out shouldEndWith "pass resume=\"${sessionOf(out)}\" to continue it)"
    }

    test("resume continues a saved session with its history") {
        val first = inSession { tool(textProvider()).execute("""{"subagent_type":"explore","prompt":"first question"}""") }
        val id = sessionOf(first)
        requests.clear()
        val second = inSession { tool(textProvider("again")).execute("""{"subagent_type":"explore","prompt":"follow up","resume":"$id"}""") }
        second shouldStartWith "again"
        sessionOf(second) shouldBe id
        requests.single().messages.toString().let { it shouldContain "first question"; it shouldContain "follow up" }
    }

    test("resume refuses another conversation's session") {
        val id = sessionOf(inSession { tool(textProvider()).execute("""{"subagent_type":"explore","prompt":"q"}""") })
        inSession("parent-2") { tool(textProvider()).execute("""{"subagent_type":"explore","prompt":"f","resume":"$id"}""") } shouldBe
            "Error: no subagent session $id under this conversation"
        inSession { tool(textProvider()).execute("""{"subagent_type":"explore","prompt":"f","resume":"nope"}""") } shouldBe
            "Error: no subagent session nope under this conversation"
    }

    test("resume refuses the wrong type") {
        val id = sessionOf(inSession { tool(textProvider()).execute("""{"subagent_type":"explore","prompt":"q"}""") })
        inSession { tool(textProvider()).execute("""{"subagent_type":"review","prompt":"f","resume":"$id"}""") } shouldBe
            "Error: session $id is a explore subagent; pass subagent_type \"explore\""
    }

    test("resume refuses a running session") {
        val bg = BackgroundSubagents()
        val gate = CompletableDeferred<Unit>()
        inSession { tool(textProvider(gate = gate), bg).execute("""{"subagent_type":"explore","prompt":"look","background":true}""") }
        val id = bg.list().single().subSessionId
        inSession { tool(textProvider(), bg).execute("""{"subagent_type":"explore","prompt":"f","resume":"$id"}""") } shouldBe
            "Error: subagent b1 is still running on that session; wait with subagent_result(\"b1\")"
        gate.complete(Unit)
        bg.close()
    }

    test("depth 1 has no background param") {
        tool(textProvider()).parametersJson shouldContain "\"background\""
        tool(textProvider(), depth = 1).parametersJson shouldNotContain "\"background\""
        tool(textProvider(), bg = null).parametersJson shouldNotContain "\"background\""
        tool(textProvider(), depth = 1).parametersJson shouldContain "\"resume\""
    }
    test("an ask rule is denied in the background even when the background policy allows all") {
        val bg = BackgroundSubagents()
        val ask = PermissionRules(listOf(PermissionRule("danger", null, Effect.ASK)), emptyList(), null, null, emptySet())
        withContext(PermissionScope(ask)) {
            inSession { tool(dangerProvider(), bg, backgroundPolicy = ConfirmationPolicy.ALLOW_ALL).execute("""{"subagent_type":"operator","prompt":"act","background":true}""") }
        }
        bg.result(null, "b1", 5)!!.status shouldBe BackgroundSubagents.Status.DONE
        executed shouldBe false
        bg.close()
    }

    test("background is refused while plan-first records the turn") {
        val bg = BackgroundSubagents()
        withContext(PlanRecorder(PlanFirst.EVERY)) {
            inSession { tool(textProvider(), bg).execute("""{"subagent_type":"explore","prompt":"look","background":true}""") }
        } shouldStartWith "Error: background subagents don't run while plan-first is on"
        bg.list() shouldBe emptyList()
        bg.close()
    }

    test("a background run keeps the session and permissions but not the parent turn's other context") {
        val seen = mutableListOf<Boolean>()
        val probe = object : Tool {
            override val name = "read_file"
            override val description = "probe"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String {
                seen += currentCoroutineContext()[Marker] != null
                seen += currentCoroutineContext()[PermissionScope] != null
                seen += currentCoroutineContext()[SessionIdContext]?.sessionId == "parent-1"
                return "ok"
            }
        }
        var n = 0
        val provider = mockk<LLMProvider>().also { p ->
            every { p.stream(any()) } answers {
                if (++n == 1) LLMResponse.ToolUse(listOf(ToolCall("c1", "read_file", "{}")), TokenUsage(1, 0)).toStreamFlow()
                else LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
            }
        }
        val bg = BackgroundSubagents()
        val t = SubagentTool(listOf(explore), provider, ToolRegistry().register(probe), FileSessionManager(sessionsDir),
            AgentConfig(model = "m"), 100_000, background = bg)
        val rules = PermissionRules(emptyList(), emptyList(), null, null, emptySet())
        withContext(Marker() + PermissionScope(rules)) {
            inSession { t.execute("""{"subagent_type":"explore","prompt":"look","background":true}""") }
        }
        bg.result(null, "b1", 5)!!.status shouldBe BackgroundSubagents.Status.DONE
        seen shouldBe listOf(false, true, true)
        bg.close()
    }

    test("a session already running in the foreground can't be resumed twice") {
        val id = sessionOf(inSession { tool(textProvider()).execute("""{"subagent_type":"explore","prompt":"q"}""") })
        val gate = CompletableDeferred<Unit>()
        val bg = BackgroundSubagents()
        requests.clear()
        coroutineScope {
            val first = async { inSession { tool(textProvider(gate = gate), bg).execute("""{"subagent_type":"explore","prompt":"a","resume":"$id"}""") } }
            while (requests.isEmpty()) kotlinx.coroutines.delay(10)
            inSession { tool(textProvider(), bg).execute("""{"subagent_type":"explore","prompt":"b","resume":"$id"}""") } shouldBe
                "Error: subagent session $id is already running; wait for it to finish"
            gate.complete(Unit)
            first.await() shouldStartWith "the answer"
        }
        bg.close()
    }
})
