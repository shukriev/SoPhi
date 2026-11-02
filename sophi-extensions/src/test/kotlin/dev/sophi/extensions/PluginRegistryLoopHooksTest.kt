package dev.sophi.extensions

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.core.agent.HookResult
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PluginRegistryLoopHooksTest : FunSpec({
    fun deciding(p: HookPoint, decide: (HookContext) -> HookResult) = object : AgentHook {
        override val point = p
        override suspend fun decide(context: HookContext) = decide(context)
    }

    fun registry(vararg hooks: AgentHook) = PluginRegistry().register(object : SophiPlugin {
        override val name = "p"
        override fun hooks() = hooks.toList()
    })

    test("BEFORE_TOOL rewrites chain, and a hook that only overrides invoke still sees the call") {
        val seen = mutableListOf<String?>()
        val plain = object : AgentHook {
            override val point = HookPoint.BEFORE_TOOL
            override suspend fun invoke(context: HookContext) { seen += context.argumentsJson }
        }
        val r = registry(
            deciding(HookPoint.BEFORE_TOOL) { HookResult.Rewrite(it.argumentsJson + "1") },
            plain,
            deciding(HookPoint.BEFORE_TOOL) { HookResult.Rewrite(it.argumentsJson + "2") },
        )
        r.beforeTool("s", "t", "a") shouldBe HookResult.Rewrite("a12")
        seen shouldBe listOf("a1")
        registry(plain).beforeTool("s", "t", "a") shouldBe HookResult.Continue
    }

    test("a BEFORE_TOOL veto wins and stops later hooks") {
        var later = false
        val r = registry(
            deciding(HookPoint.BEFORE_TOOL) { HookResult.Veto("no") },
            deciding(HookPoint.BEFORE_TOOL) { later = true; HookResult.Continue },
        )
        r.beforeTool("s", "t", "{}") shouldBe HookResult.Veto("no")
        later shouldBe false
    }

    test("ON_CONFIRMATION hooks can deny only") {
        val req = ConfirmationRequest("c1", "rm", "{}", RiskLevel.DESTRUCTIVE)
        registry(deciding(HookPoint.ON_CONFIRMATION) { HookResult.Veto("not ${it.toolName}") }).denies("s", req) shouldBe "not rm"
        registry(deciding(HookPoint.ON_CONFIRMATION) { HookResult.Rewrite("{}") }).denies("s", req) shouldBe null
    }

    test("BEFORE_PROVIDER_REQUEST hooks edit the request in turn") {
        val r = registry(
            deciding(HookPoint.BEFORE_PROVIDER_REQUEST) { HookResult.Request(it.request!!.copy(systemPrompt = "a")) },
            deciding(HookPoint.BEFORE_PROVIDER_REQUEST) { HookResult.Request(it.request!!.copy(systemPrompt = it.request!!.systemPrompt + "b")) },
        )
        r.beforeRequest("s", CompletionRequest(messages = emptyList(), model = "m")).systemPrompt shouldBe "ab"
    }

    test("AFTER_TOOL and compaction hooks get their contexts") {
        val got = mutableListOf<Pair<HookPoint, HookContext>>()
        val r = registry(*listOf(HookPoint.AFTER_TOOL, HookPoint.BEFORE_COMPACTION, HookPoint.AFTER_COMPACTION).map { p ->
            object : AgentHook {
                override val point = p
                override suspend fun invoke(context: HookContext) { got += p to context }
            }
        }.toTypedArray())
        r.afterTool("s", "t", "{}", "out", failed = true, durationMillis = 7)
        r.compaction("s", after = false)
        r.compaction("s", after = true)
        got.map { it.first } shouldBe listOf(HookPoint.AFTER_TOOL, HookPoint.BEFORE_COMPACTION, HookPoint.AFTER_COMPACTION)
        got[0].second shouldBe HookContext("s", toolName = "t", argumentsJson = "{}", toolResult = "out", success = false, durationMillis = 7)
    }
})
