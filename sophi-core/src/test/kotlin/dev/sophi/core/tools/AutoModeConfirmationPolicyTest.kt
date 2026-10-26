package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import dev.sophi.core.audit.DecisionNotes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

    test("auto-approved calls are noted auto:rule or auto:classifier; forwarded ones are left to the fallback") {
        val registry = ToolRegistry()
            .register(FixedRuleTool("rule_ok", RuleVerdict.LOW_RISK))
            .register(FixedRuleTool("ask_classifier", RuleVerdict.UNKNOWN))
            .register(FixedRuleTool("risky_tool", RuleVerdict.HIGH_RISK))
        val classifier = RiskClassifier { _, _, _, _ -> RuleVerdict.LOW_RISK }
        val fallback = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        val policy = AutoModeConfirmationPolicy(registry, classifier, fallback)
        val notes = DecisionNotes()

        runBlocking {
            withContext(notes) {
                policy.confirm(listOf(
                    ConfirmationRequest("c1", "rule_ok", "{}", RiskLevel.DESTRUCTIVE),
                    ConfirmationRequest("c2", "ask_classifier", "{}", RiskLevel.DESTRUCTIVE),
                    ConfirmationRequest("c3", "risky_tool", "{}", RiskLevel.DESTRUCTIVE)
                ))
            }
        }

        notes.rules shouldBe mapOf("c1" to "auto:rule", "c2" to "auto:classifier")
    }

    test("an ask-rule request reaches the fallback under the god-mode policy") {
        val registry = ToolRegistry().register(FixedRuleTool("t", RuleVerdict.UNKNOWN))
        val seen = mutableListOf<String>()
        val fallback = ConfirmationPolicy { requests -> seen += requests.map { it.callId }; requests.associate { it.callId to false } }
        val policy = AutoModeConfirmationPolicy(registry, RiskClassifier.ALWAYS_LOW_RISK, fallback, "allow-all")

        val result = runBlocking { policy.confirm(listOf(ConfirmationRequest("c1", "t", "{}", RiskLevel.SAFE, askRule = true))) }

        result shouldBe mapOf("c1" to false)
        seen shouldBe listOf("c1")
    }

    test("an ask-rule request reaches the fallback under auto mode even when rule and classifier say LOW_RISK") {
        val registry = ToolRegistry().register(FixedRuleTool("low", RuleVerdict.LOW_RISK)).register(FixedRuleTool("unk", RuleVerdict.UNKNOWN))
        val seen = mutableListOf<String>()
        val fallback = ConfirmationPolicy { requests -> seen += requests.map { it.callId }; requests.associate { it.callId to false } }
        val policy = AutoModeConfirmationPolicy(registry, { _, _, _, _ -> RuleVerdict.LOW_RISK }, fallback)

        val result = runBlocking {
            policy.confirm(listOf(
                ConfirmationRequest("c1", "low", "{}", RiskLevel.SAFE, askRule = true),
                ConfirmationRequest("c2", "unk", "{}", RiskLevel.SAFE, askRule = true),
            ))
        }

        result shouldBe mapOf("c1" to false, "c2" to false)
        seen shouldBe listOf("c1", "c2")
    }
})
