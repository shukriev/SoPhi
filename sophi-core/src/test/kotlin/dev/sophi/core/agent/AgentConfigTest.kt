package dev.sophi.core.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AgentConfigTest : FunSpec({
    test("maxToolRounds defaults to a loose sanity ceiling, not the primary per-turn bound") {
        // Context-usage tracking + mid-loop compaction is what actually bounds a turn now;
        // this number only exists to stop a pathological, context-light infinite loop.
        AgentConfig(model = "m").maxToolRounds shouldBe 200
    }

    test("maxBranchLength is unchanged — cross-turn compaction is a separate concern") {
        AgentConfig(model = "m").maxBranchLength shouldBe 50
    }
})
