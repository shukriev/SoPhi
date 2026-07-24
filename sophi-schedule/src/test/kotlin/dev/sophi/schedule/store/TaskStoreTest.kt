package dev.sophi.schedule.store

import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull

class TaskStoreTest : FunSpec({
    fun store() = TaskStore(tempdir().toPath().resolve("tasks.json"))

    test("add computes nextRunAtMs for an Interval trigger and persists the task") {
        val s = store()
        val before = System.currentTimeMillis()
        val saved = s.add(ScheduledTask(
            name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        saved.nextRunAtMs.shouldNotBeNull()
        (saved.nextRunAtMs!! >= before + 60_000) shouldBe true
        s.list().single().id shouldBe saved.id
    }

    test("add sets nextRunAtMs to atMs for a Once trigger") {
        val s = store()
        val saved = s.add(ScheduledTask(
            name = "t", trigger = Trigger.Once(atMs = 12345L), mode = TaskMode.Recurring, prompt = "p"))
        saved.nextRunAtMs shouldBe 12345L
    }

    test("add leaves nextRunAtMs null for a Manual trigger") {
        val s = store()
        val saved = s.add(ScheduledTask(
            name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        saved.nextRunAtMs.shouldBeNull()
    }

    test("get returns null for an unknown id") {
        store().get("no-such-id") shouldBe null
    }

    test("setEnabled(false) pauses; setEnabled(true) resumes and reschedules") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        s.setEnabled(task.id, false) shouldBe true
        s.get(task.id)!!.enabled shouldBe false
        val before = System.currentTimeMillis()
        s.setEnabled(task.id, true) shouldBe true
        val resumed = s.get(task.id)!!
        resumed.enabled shouldBe true
        (resumed.nextRunAtMs!! >= before + 60_000) shouldBe true
    }

    test("setEnabled returns false for an unknown id") {
        store().setEnabled("no-such-id", true) shouldBe false
    }

    test("remove deletes the task; returns false if it never existed") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        s.remove(task.id) shouldBe true
        s.list() shouldBe emptyList()
        s.remove(task.id) shouldBe false
    }

    test("recordRun reschedules an Interval task from finishedAtMs and bumps iterationCount") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        s.recordRun(task.id, finishedAtMs = 1_000_000L) shouldBe true
        val updated = s.get(task.id)!!
        updated.lastRunAtMs shouldBe 1_000_000L
        updated.nextRunAtMs shouldBe 1_060_000L
        updated.iterationCount shouldBe 1
    }

    test("recordRun on a Once task clears nextRunAtMs and disables it") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Once(atMs = 500L), mode = TaskMode.Recurring, prompt = "p"))
        s.recordRun(task.id, finishedAtMs = 500L) shouldBe true
        val updated = s.get(task.id)!!
        updated.nextRunAtMs.shouldBeNull()
        updated.enabled shouldBe false
    }

    test("recordRun returns false for an unknown id") {
        store().recordRun("no-such-id", finishedAtMs = 1L) shouldBe false
    }

    test("update() applies the transform and persists it") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        s.update(task.id) { it.copy(name = "renamed", prompt = "new prompt") } shouldBe true
        val updated = s.get(task.id)!!
        updated.name shouldBe "renamed"
        updated.prompt shouldBe "new prompt"
    }

    test("update() recomputes nextRunAtMs when the transform changes the trigger") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val before = System.currentTimeMillis()
        s.update(task.id) { it.copy(trigger = Trigger.Interval(3600)) } shouldBe true
        val updated = s.get(task.id)!!
        (updated.trigger as Trigger.Interval).everySeconds shouldBe 3600
        (updated.nextRunAtMs!! >= before + 3600_000) shouldBe true
    }

    test("update() leaves nextRunAtMs untouched when the trigger doesn't change") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val originalNextRun = s.get(task.id)!!.nextRunAtMs
        s.update(task.id) { it.copy(name = "renamed") } shouldBe true
        s.get(task.id)!!.nextRunAtMs shouldBe originalNextRun
    }

    test("update() returns false for an unknown id") {
        store().update("no-such-id") { it } shouldBe false
    }
})
