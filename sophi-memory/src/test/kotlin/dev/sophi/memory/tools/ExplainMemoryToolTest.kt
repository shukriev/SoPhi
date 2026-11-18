package dev.sophi.memory.tools

import dev.sophi.core.tools.RiskLevel
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import dev.sophi.memory.jane.Memory
import dev.sophi.memory.jane.Origin
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

class ExplainMemoryToolTest : FunSpec({
    fun palace(id: String? = null): JanesPalace {
        val home = tempdir().toPath()
        if (id != null) PalaceStore(home).apply {
            upsertMemory(Memory(id, "The family shops on Saturdays", Room.KNOWLEDGE, 0.5,
                SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.PERSONAL, Provenance.USER_DIRECT,
                0L, 0L, "s", origin = Origin.SHARED, sharedBy = listOf("Sam")))
        }.close()
        return JanesPalace(JanesPalaceConfig(home = home, sessionModel = "m"), null, null)
    }

    test("explains a memory from whichever palace has it; an unknown id says so") {
        val tool = ExplainMemoryTool(listOf(palace(), palace("mem_h")))
        tool.name shouldBe "explain_memory"
        tool.riskLevel("{}") shouldBe RiskLevel.SAFE
        runBlocking { tool.execute("""{"id":"mem_h"}""") } shouldContain "shared into the household by Sam"
        runBlocking { tool.execute("""{"id":"mem_x"}""") } shouldContain "No memory"
        runBlocking { tool.execute("{}") } shouldContain "No memory"
    }
})
