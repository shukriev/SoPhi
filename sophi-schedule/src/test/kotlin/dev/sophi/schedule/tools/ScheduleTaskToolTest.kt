package dev.sophi.schedule.tools

import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class ScheduleTaskToolTest : FunSpec({
    fun store() = TaskStore(tempdir().toPath().resolve("tasks.json"))

    test("name is manage_scheduled_task") {
        ScheduleTaskTool(store()).name shouldContain "manage_scheduled_task"
    }

    test("create with trigger_type=interval and mode=recurring persists a task") {
        val s = store()
        val tool = ScheduleTaskTool(s)
        val result = runBlocking {
            tool.execute("""{"action":"create","name":"monitor","prompt":"check my feed","trigger_type":"interval","every_seconds":3600,"mode":"recurring"}""")
        }
        result shouldContain "Created task"
        val task = s.list().single()
        task.name shouldContain "monitor"
        (task.trigger is Trigger.Interval) shouldBe true
    }

    test("create with mode=goal and stop_condition_type=shell_check persists a Goal task") {
        val s = store()
        val tool = ScheduleTaskTool(s)
        runBlocking {
            tool.execute("""{"action":"create","name":"g","prompt":"fix it","trigger_type":"manual","mode":"goal","stop_condition_type":"shell_check","shell_command":"./t.sh","max_iterations":3}""")
        }
        s.list().single().name shouldContain "g"
    }

    test("create without required fields returns an Error string and does not persist") {
        val s = store()
        val result = runBlocking {
            ScheduleTaskTool(s).execute("""{"action":"create","trigger_type":"interval","every_seconds":60,"mode":"recurring"}""")
        }
        result shouldContain "Error"
        s.list() shouldBe emptyList()
    }

    test("list renders enabled/paused state and mode") {
        val s = store()
        s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val result = runBlocking { ScheduleTaskTool(s).execute("""{"action":"list"}""") }
        result shouldContain "enabled"
        result shouldContain "t"
    }

    test("pause and resume round-trip via task_id") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val tool = ScheduleTaskTool(s)
        runBlocking { tool.execute("""{"action":"pause","task_id":"${task.id}"}""") }
        s.get(task.id)!!.enabled shouldBe false
        runBlocking { tool.execute("""{"action":"resume","task_id":"${task.id}"}""") }
        s.get(task.id)!!.enabled shouldBe true
    }

    test("remove deletes the task; unknown id returns an Error string") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val tool = ScheduleTaskTool(s)
        val ok = runBlocking { tool.execute("""{"action":"remove","task_id":"${task.id}"}""") }
        ok shouldContain "Removed"
        val err = runBlocking { tool.execute("""{"action":"remove","task_id":"no-such-id"}""") }
        err shouldContain "Error"
    }

    test("unknown action returns an Error string") {
        val result = runBlocking { ScheduleTaskTool(store()).execute("""{"action":"bogus"}""") }
        result shouldContain "Error"
    }
})
