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
        tool.riskLevel shouldBe RiskLevel.SAFE
    }

    test("riskLevel can be overridden to DESTRUCTIVE") {
        val tool = object : Tool {
            override val name = "danger"
            override val description = "does something risky"
            override val parametersJson = "{}"
            override val riskLevel = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "done"
        }
        tool.riskLevel shouldBe RiskLevel.DESTRUCTIVE
    }
})
