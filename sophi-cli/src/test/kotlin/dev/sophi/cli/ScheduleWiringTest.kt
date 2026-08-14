package dev.sophi.cli

import dev.sophi.sdk.DefaultPrompt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class ScheduleWiringTest : FunSpec({
    test("scheduledTaskSystemPrompt includes both the base prompt and the unattended addendum") {
        val prompt = scheduledTaskSystemPrompt()
        prompt shouldContain DefaultPrompt.BASE
        prompt shouldContain DefaultPrompt.UNATTENDED
    }
})
