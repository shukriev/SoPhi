package dev.sophi.schedule.notify

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MacNotifierTest : FunSpec({
    val task = ScheduledTask(id = "t1", name = "Twitter monitor", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p")

    test("notify shells out to osascript with title and summary") {
        var captured: List<String>? = null
        val notifier = MacNotifier(runCommand = { cmd -> captured = cmd; 0 })
        notifier.notify(task, RunRecord("t1", 0L, 1L, RunOutcome.Succeeded, "nothing new"))

        captured.shouldNotBeNull()
        captured!![0] shouldBe "osascript"
        captured!![1] shouldBe "-e"
        captured!![2] shouldContain "Twitter monitor"
        captured!![2] shouldContain "nothing new"
    }

    test("notify escapes double quotes in the summary so the AppleScript stays well-formed") {
        var captured: List<String>? = null
        val notifier = MacNotifier(runCommand = { cmd -> captured = cmd; 0 })
        notifier.notify(task, RunRecord("t1", 0L, 1L, RunOutcome.Succeeded, """found "urgent" post"""))

        captured!![2] shouldContain """\"urgent\""""
    }

    test("notify swallows a non-zero exit / thrown exception from runCommand rather than propagating") {
        val notifier = MacNotifier(runCommand = { throw java.io.IOException("osascript missing") })
        notifier.notify(task, RunRecord("t1", 0L, 1L, RunOutcome.Failed("boom"), ""))
        // no assertion needed beyond "did not throw"
    }
})
