package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class HarnessConfigTest : FunSpec({
    test("hash() is stable for identical configs") {
        val a = HarnessConfig(systemPrompt = "prompt", temperature = 0.7, maxTokens = 4096, maxRecalledLessons = 10)
        val b = HarnessConfig(systemPrompt = "prompt", temperature = 0.7, maxTokens = 4096, maxRecalledLessons = 10)

        a.hash() shouldBe b.hash()
    }

    test("hash() differs when any field changes") {
        val a = HarnessConfig(systemPrompt = "prompt", temperature = 0.7, maxTokens = 4096, maxRecalledLessons = 10)
        val b = a.copy(temperature = 0.9)

        a.hash() shouldNotBe b.hash()
    }

    test("hash() differs for a change to toolDescriptionOverrides") {
        val a = HarnessConfig()
        val b = a.copy(toolDescriptionOverrides = mapOf("bash" to "custom description"))

        a.hash() shouldNotBe b.hash()
    }
})
