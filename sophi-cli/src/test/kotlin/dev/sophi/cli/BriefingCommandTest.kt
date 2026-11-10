package dev.sophi.cli

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

class BriefingCommandTest : FunSpec({
    test("no model reachable: prints the template from the settings' schedule dir and starts the next briefing here") {
        val dir = createTempDirectory("briefing-cli")
        val now = System.currentTimeMillis()
        val t = TaskStore(dir.resolve("tasks.json")).add(
            ScheduledTask(name = "Inbox triage", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        RunLog(dir.resolve("runs.jsonl")).append(RunRecord(t.id, now - 2000, now - 1000, RunOutcome.Succeeded, "Archived 3."))

        val text = runBlocking { runBriefing(mapOf("schedule-dir" to dir.toString()), now) { error("no local model server found") } }

        text shouldContain "Done (1)"
        text shouldContain "Inbox triage"
        dir.resolve("briefing-last").readText() shouldBe now.toString()
        runBlocking { runBriefing(mapOf("schedule-dir" to dir.toString()), now + 1) { error("unused") } } shouldContain "Nothing ran since"
    }
})
