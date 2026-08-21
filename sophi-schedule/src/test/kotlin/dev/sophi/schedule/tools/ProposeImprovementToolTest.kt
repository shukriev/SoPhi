package dev.sophi.schedule.tools

import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class ProposeImprovementToolTest : FunSpec({
    test("riskLevel is always SAFE") {
        val tool = ProposeImprovementTool()
        tool.riskLevel("""{"title":"x","category":"other","rationale":"r","suggestedAction":"a"}""") shouldBe RiskLevel.SAFE
    }

    test("execute returns a fixed acknowledgment") {
        val tool = ProposeImprovementTool()
        val result = runBlocking { tool.execute("""{"title":"x","category":"other","rationale":"r","suggestedAction":"a"}""") }
        result shouldBe "Proposal recorded for review."
    }
})
