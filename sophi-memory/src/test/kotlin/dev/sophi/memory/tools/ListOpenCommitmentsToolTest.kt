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

class ListOpenCommitmentsToolTest : FunSpec({
    test("riskLevel is SAFE") {
        val home = tempdir().toPath()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        ListOpenCommitmentsTool(palace).riskLevel("{}") shouldBe RiskLevel.SAFE
        palace.close()
    }

    test("execute lists open commitment texts") {
        val home = tempdir().toPath()
        val seed = PalaceStore(home)
        val nowMs = System.currentTimeMillis()
        seed.upsertMemory(Memory(
            "mem_a", "call Mark back about the contract", Room.TASKS, 0.5,
            SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.PERSONAL, Provenance.USER_DIRECT,
            nowMs, nowMs, "s", isCommitment = true
        ))
        seed.close()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        val result = runBlocking { ListOpenCommitmentsTool(palace).execute("{}") }
        result shouldContain "call Mark back about the contract"
        palace.close()
    }

    test("execute with no open commitments returns a clear empty message") {
        val home = tempdir().toPath()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        val result = runBlocking { ListOpenCommitmentsTool(palace).execute("{}") }
        result shouldBe "No open commitments."
        palace.close()
    }
})
