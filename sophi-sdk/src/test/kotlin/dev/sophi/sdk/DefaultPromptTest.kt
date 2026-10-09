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

    // GAIA (sophi-arena): median 1 tool call per question on qwen3-32b; 6 runs "gave up" without trying,
    // one claimed a file it never wrote. Their effect is measured in sophi-arena, not here.
    test("the base prompt says to check with tools before concluding something is unavailable") {
        DefaultPrompt.BASE shouldContain "before concluding"
    }

    test("the base prompt says to try a different route after a failed tool call") {
        DefaultPrompt.BASE shouldContain "different route"
    }

    test("the base prompt allows claiming an action only when a tool result shows it") {
        DefaultPrompt.BASE shouldContain "only if a tool result"
    }
})
