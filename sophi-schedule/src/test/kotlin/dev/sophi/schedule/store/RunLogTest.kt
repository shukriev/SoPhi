package dev.sophi.schedule.store

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class RunLogTest : FunSpec({
    fun log() = RunLog(tempdir().toPath().resolve("runs.jsonl"))
    fun record(taskId: String, started: Long) = RunRecord(taskId, started, started + 1, RunOutcome.Succeeded, "ok")

    test("append then readAll round-trips records in order") {
        val l = log()
        l.append(record("t1", 1L))
        l.append(record("t1", 2L))
        l.readAll() shouldBe listOf(record("t1", 1L), record("t1", 2L))
    }

    test("readAll on a missing file returns empty") {
        log().readAll() shouldBe emptyList()
    }

    test("forTask filters by taskId") {
        val l = log()
        l.append(record("t1", 1L))
        l.append(record("t2", 2L))
        l.forTask("t2") shouldBe listOf(record("t2", 2L))
    }

    test("tail returns the last n records") {
        val l = log()
        l.append(record("t1", 1L))
        l.append(record("t1", 2L))
        l.append(record("t1", 3L))
        l.tail(2) shouldBe listOf(record("t1", 2L), record("t1", 3L))
    }
})
