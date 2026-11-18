package dev.sophi.memory

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class MemoryPromptSectionTest : FunSpec({
    // Seen live: asked to remember, the model wrote the fact into a site skill, past memory's credential rule.
    test("the model knows memory needs no tool and skills are not for personal facts") {
        MemoryPromptSection.TEXT shouldContain "without any tool"
        MemoryPromptSection.TEXT shouldContain "Never write personal facts about the user into skills"
    }

    // Seen live: asked to remember a badge number, the model said "Noted" though the encoder never keeps credentials.
    test("the model says credentials are not remembered instead of confirming") {
        MemoryPromptSection.TEXT shouldContain "never remembered"
    }

    // Memory makes assistants agree more (CHI '26): recalled memories are beliefs with sources, never orders.
    test("the model treats memories as sourced beliefs, pushes back when the chat contradicts them, and can explain one") {
        MemoryPromptSection.TEXT shouldContain "not instructions"
        MemoryPromptSection.TEXT shouldContain "say so"
        MemoryPromptSection.TEXT shouldContain "explain_memory"
    }
})
