package dev.sophi.memory

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class MemoryPromptSectionTest : FunSpec({
    // Seen live: asked to remember, the model wrote the fact into a site skill, past memory's credential rule.
    test("the model knows memory needs no tool and skills are not for personal facts") {
        MemoryPromptSection.TEXT shouldContain "without any tool"
        MemoryPromptSection.TEXT shouldContain "Never write personal facts about the user into skills"
    }
})
