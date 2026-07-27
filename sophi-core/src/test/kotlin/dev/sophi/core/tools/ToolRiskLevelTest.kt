package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ToolRiskLevelTest : FunSpec({
    test("riskLevel defaults to SAFE when a Tool implementation does not override it") {
        val tool = object : Tool {
            override val name = "noop"
            override val description = "does nothing"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "ok"
        }
        tool.riskLevel("{}") shouldBe RiskLevel.SAFE
    }

    test("riskLevel can be overridden to DESTRUCTIVE") {
        val tool = object : Tool {
            override val name = "danger"
            override val description = "does something risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "done"
        }
        tool.riskLevel("{}") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("riskLevel can inspect the arguments it's given") {
        val tool = object : Tool {
            override val name = "conditional"
            override val description = "risk depends on args"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) =
                if (argumentsJson.contains("safe")) RiskLevel.SAFE else RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "done"
        }
        tool.riskLevel("""{"mode":"safe"}""") shouldBe RiskLevel.SAFE
        tool.riskLevel("""{"mode":"other"}""") shouldBe RiskLevel.DESTRUCTIVE
    }
})
