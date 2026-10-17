package dev.sophi.core.context

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import io.kotest.matchers.shouldBe

/** What a strict chat template needs after compaction: no SYSTEM message, TOOL only after its call, no USER, USER. */
fun assertTemplateSafe(messages: List<Message>) {
    messages.none { it.role == MessageRole.SYSTEM } shouldBe true
    messages.zipWithNext().forEach { (a, b) ->
        if (b.role == MessageRole.TOOL) (a.role == MessageRole.TOOL || (a.role == MessageRole.ASSISTANT && a.toolCalls != null)) shouldBe true
        if (a.role == MessageRole.USER) (b.role != MessageRole.USER) shouldBe true
    }
}
