package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ToolRuleVerdictTest : FunSpec({
    test("ruleVerdict defaults to UNKNOWN when a Tool implementation does not override it") {
        val tool = object : Tool {
            override val name = "noop"
            override val description = "does nothing"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "ok"
        }
        tool.ruleVerdict("{}") shouldBe RuleVerdict.UNKNOWN
    }

    test("ruleVerdict can be overridden to LOW_RISK or HIGH_RISK") {
        val tool = object : Tool {
            override val name = "conditional"
            override val description = "risk depends on args"
            override val parametersJson = "{}"
            override fun ruleVerdict(argumentsJson: String) =
                if (argumentsJson.contains("safe")) RuleVerdict.LOW_RISK else RuleVerdict.HIGH_RISK
            override suspend fun execute(argumentsJson: String) = "done"
        }
        tool.ruleVerdict("""{"mode":"safe"}""") shouldBe RuleVerdict.LOW_RISK
        tool.ruleVerdict("""{"mode":"other"}""") shouldBe RuleVerdict.HIGH_RISK
    }
})
