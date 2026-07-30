package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

private class FixedRuleTool(
    override val name: String,
    private val verdict: RuleVerdict
) : Tool {
    override val description = "test tool"
    override val parametersJson = "{}"
    override fun ruleVerdict(argumentsJson: String) = verdict
    override suspend fun execute(argumentsJson: String) = "ok"
}

class AutoModeConfirmationPolicyTest : FunSpec({
    test("LOW_RISK rule verdict auto-approves without calling the classifier or fallback") {
        val registry = ToolRegistry().register(FixedRuleTool("safe_tool", RuleVerdict.LOW_RISK))
        var classifierCalls = 0
        val classifier = RiskClassifier { _, _, _, _ -> classifierCalls++; RuleVerdict.HIGH_RISK }
        var fallbackCalls = 0
        val fallback = ConfirmationPolicy { requests -> fallbackCalls++; requests.associate { it.callId to false } }
        val policy = AutoModeConfirmationPolicy(registry, classifier, fallback)

        val result = runBlocking {
            policy.confirm(listOf(ConfirmationRequest("c1", "safe_tool", "{}", RiskLevel.DESTRUCTIVE)))
        }

        result shouldBe mapOf("c1" to true)
        classifierCalls shouldBe 0
        fallbackCalls shouldBe 0
    }

    test("HIGH_RISK rule verdict is sent to the fallback policy without calling the classifier") {
        val registry = ToolRegistry().register(FixedRuleTool("risky_tool", RuleVerdict.HIGH_RISK))
        var classifierCalls = 0
        val classifier = RiskClassifier { _, _, _, _ -> classifierCalls++; RuleVerdict.LOW_RISK }
        val fallback = ConfirmationPolicy { requests -> requests.associate { it.callId to true } }
        val policy = AutoModeConfirmationPolicy(registry, classifier, fallback)

        val result = runBlocking {
            policy.confirm(listOf(ConfirmationRequest("c1", "risky_tool", "{}", RiskLevel.DESTRUCTIVE)))
        }

        result shouldBe mapOf("c1" to true)
        classifierCalls shouldBe 0
    }

    test("UNKNOWN rule verdict falls back to the classifier") {
        val registry = ToolRegistry().register(FixedRuleTool("ambiguous_tool", RuleVerdict.UNKNOWN))
        val classifier = RiskClassifier { _, _, _, _ -> RuleVerdict.LOW_RISK }
        var fallbackCalls = 0
        val fallback = ConfirmationPolicy { requests -> fallbackCalls++; requests.associate { it.callId to false } }
        val policy = AutoModeConfirmationPolicy(registry, classifier, fallback)

        val result = runBlocking {
            policy.confirm(listOf(ConfirmationRequest("c1", "ambiguous_tool", "{}", RiskLevel.DESTRUCTIVE)))
        }

        result shouldBe mapOf("c1" to true)
        fallbackCalls shouldBe 0
    }

    test("a mixed batch auto-approves low-risk calls and batches the rest into one fallback call") {
        val registry = ToolRegistry()
            .register(FixedRuleTool("safe_tool", RuleVerdict.LOW_RISK))
            .register(FixedRuleTool("risky_tool", RuleVerdict.HIGH_RISK))
        val classifier = RiskClassifier { _, _, _, _ -> RuleVerdict.HIGH_RISK }
        val fallbackBatchSizes = mutableListOf<Int>()
        val fallback = ConfirmationPolicy { requests ->
            fallbackBatchSizes.add(requests.size)
            requests.associate { it.callId to false }
        }
        val policy = AutoModeConfirmationPolicy(registry, classifier, fallback)

        val result = runBlocking {
            policy.confirm(listOf(
                ConfirmationRequest("c1", "safe_tool", "{}", RiskLevel.DESTRUCTIVE),
                ConfirmationRequest("c2", "risky_tool", "{}", RiskLevel.DESTRUCTIVE)
            ))
        }

        result shouldBe mapOf("c1" to true, "c2" to false)
        fallbackBatchSizes shouldBe listOf(1)
    }

    test("a missing tool fails safe to the fallback policy") {
        val registry = ToolRegistry()
        val classifier = RiskClassifier { _, _, _, _ -> RuleVerdict.LOW_RISK }
        val fallback = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        val policy = AutoModeConfirmationPolicy(registry, classifier, fallback)

        val result = runBlocking {
            policy.confirm(listOf(ConfirmationRequest("c1", "ghost_tool", "{}", RiskLevel.DESTRUCTIVE)))
        }

        result shouldBe mapOf("c1" to false)
    }
})
