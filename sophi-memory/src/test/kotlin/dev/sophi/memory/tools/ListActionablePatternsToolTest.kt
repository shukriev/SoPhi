package dev.sophi.memory.tools

import dev.sophi.core.tools.RiskLevel
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import dev.sophi.memory.jane.Memory
import dev.sophi.memory.jane.PalaceStore
import dev.sophi.memory.jane.Provenance
import dev.sophi.memory.jane.Room
import dev.sophi.memory.jane.SalienceSignals
import dev.sophi.memory.jane.Sensitivity
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class ListActionablePatternsToolTest : FunSpec({
    test("riskLevel is SAFE") {
        val home = tempdir().toPath()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        ListActionablePatternsTool(palace).riskLevel("{}") shouldBe RiskLevel.SAFE
        palace.close()
    }

    test("execute lists actionable pattern texts") {
        val home = tempdir().toPath()
        val seed = PalaceStore(home)
        seed.upsertMemory(Memory(
            "mem_a", "always forgets passport before flights", Room.EPISODES, 0.5,
            SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.PERSONAL, Provenance.USER_DIRECT,
            0L, 0L, "s", actionablePattern = true
        ))
        seed.close()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        val result = runBlocking { ListActionablePatternsTool(palace).execute("{}") }
        result shouldContain "always forgets passport before flights"
        palace.close()
    }

    test("execute with no patterns returns a clear empty message") {
        val home = tempdir().toPath()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        val result = runBlocking { ListActionablePatternsTool(palace).execute("{}") }
        result shouldBe "No actionable patterns remembered."
        palace.close()
    }
})
