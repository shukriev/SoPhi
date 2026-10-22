package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.TaskStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

class ScheduleRunDueCommandTest : FunSpec({
    // `--provider claude` without `--model` fails at resolution, so it shows whether run-due resolved at all.
    fun runDue(scheduleDir: java.nio.file.Path) = ScheduleRunDueCommand().parse(
        listOf("--schedule-dir", scheduleDir.toString(), "--provider", "claude")
    )

    test("nothing due: exits without resolving a provider") {
        val dir = createTempDirectory("run-due-idle")
        TaskStore(dir.resolve("tasks.json")).add(ScheduledTask(
            name = "later", trigger = Trigger.Once(System.currentTimeMillis() + 3_600_000), mode = TaskMode.Recurring, prompt = "p"
        ))
        runDue(dir) // no UsageError
    }

    test("a due task resolves the provider") {
        val dir = createTempDirectory("run-due-due")
        TaskStore(dir.resolve("tasks.json")).add(ScheduledTask(
            name = "now", trigger = Trigger.Once(System.currentTimeMillis() - 1000), mode = TaskMode.Recurring, prompt = "p"
        ))
        shouldThrow<UsageError> { runDue(dir) }.message shouldContain "--model is required"
    }
})
