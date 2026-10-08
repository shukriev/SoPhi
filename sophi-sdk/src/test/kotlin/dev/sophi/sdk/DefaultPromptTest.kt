package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class DefaultPromptTest : FunSpec({
    // Playbook baseline4: half the failures were "done" claims nobody checked — 6 of 12 files written
    // then 12 listed, a symptom patch with no test run, an existing file "didn't exist" and was
    // overwritten, an answer left in chat instead of the requested file.
    test("the base prompt asks for a check before claiming done") {
        with(DefaultPrompt.BASE) {
            this shouldContain "run the project's tests"
            this shouldContain "list or read"
            this shouldContain "write it to that file"
            this shouldContain "read it first and keep its content"
        }
    }
})
