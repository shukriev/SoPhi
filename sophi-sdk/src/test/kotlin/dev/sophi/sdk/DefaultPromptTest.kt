package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class DefaultPromptTest : FunSpec({
    test("BASE identifies the agent as Sophi") {
        DefaultPrompt.BASE shouldContain "Sophi"
    }

    test("UNATTENDED explains the run has no one watching") {
        DefaultPrompt.UNATTENDED shouldContain "unattended"
    }
})
