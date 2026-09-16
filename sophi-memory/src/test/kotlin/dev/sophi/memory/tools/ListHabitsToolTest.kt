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

class ListHabitsToolTest : FunSpec({
    test("riskLevel is SAFE") {
        val home = tempdir().toPath()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        ListHabitsTool(palace).riskLevel("{}") shouldBe RiskLevel.SAFE
        palace.close()
    }

    test("execute lists habit texts with their timing") {
        val home = tempdir().toPath()
        val seed = PalaceStore(home)
        seed.upsertMemory(Memory(
            "mem_a", "checks budget dashboard", Room.EPISODES, 0.5,
            SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.PERSONAL, Provenance.USER_DIRECT,
            0L, 0L, "s", habitConfidence = 0.8, habitPreferredHour = 8, habitPreferredDayOfWeek = 1
        ))
        seed.close()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        val result = runBlocking { ListHabitsTool(palace).execute("{}") }
        result shouldContain "checks budget dashboard"
        result shouldContain "8:00"
        palace.close()
    }

    test("execute with no habits returns a clear empty message") {
        val home = tempdir().toPath()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        val result = runBlocking { ListHabitsTool(palace).execute("{}") }
        result shouldBe "No habits remembered yet."
        palace.close()
    }

    test("habits() excludes memories with sensitivity above PERSONAL, same ceiling as actionablePatterns") {
        val home = tempdir().toPath()
        val seed = PalaceStore(home)
        seed.upsertMemory(Memory(
            "mem_sensitive", "sensitive habit", Room.EPISODES, 0.5,
            SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.SENSITIVE, Provenance.USER_DIRECT,
            0L, 0L, "s", habitConfidence = 0.8, habitPreferredHour = 8
        ))
        seed.close()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        palace.habits() shouldBe emptyList()
        palace.close()
    }
})
