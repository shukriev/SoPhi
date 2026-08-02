package dev.sophi.schedule.model

import dev.sophi.core.agent.plan.StopCondition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class SerializationRoundTripTest : FunSpec({
    val json = Json { encodeDefaults = true }

    test("ScheduledTask with Interval trigger and Recurring mode round-trips") {
        val task = ScheduledTask(
            id = "task_1",
            name = "monitor",
            trigger = Trigger.Interval(everySeconds = 3600),
            mode = TaskMode.Recurring,
            prompt = "check my feed",
            toolGrants = setOf("fetch_url"),
            createdAtMs = 1000L
        )
        val encoded = json.encodeToString(task)
        json.decodeFromString<ScheduledTask>(encoded) shouldBe task
    }

    test("ScheduledTask with Once trigger and Goal/ShellCheck mode round-trips") {
        val task = ScheduledTask(
            id = "task_2",
            name = "retry-until-green",
            trigger = Trigger.Once(atMs = 5000L),
            mode = TaskMode.Goal(StopCondition.ShellCheck("./run-tests.sh", expectExitZero = true), maxIterations = 3),
            prompt = "fix the failing test",
            createdAtMs = 1000L
        )
        val encoded = json.encodeToString(task)
        json.decodeFromString<ScheduledTask>(encoded) shouldBe task
    }

    test("ScheduledTask with Manual trigger and Goal/LlmJudged mode round-trips") {
        val task = ScheduledTask(
            id = "task_3",
            name = "goal-task",
            trigger = Trigger.Manual,
            mode = TaskMode.Goal(StopCondition.LlmJudged, maxIterations = 5),
            prompt = "keep trying",
            createdAtMs = 1000L
        )
        val encoded = json.encodeToString(task)
        json.decodeFromString<ScheduledTask>(encoded) shouldBe task
    }

    test("RunRecord with Failed outcome round-trips") {
        val record = RunRecord(
            taskId = "task_1", startedAtMs = 1L, finishedAtMs = 2L,
            outcome = RunOutcome.Failed("boom"), summary = ""
        )
        val encoded = json.encodeToString(record)
        json.decodeFromString<RunRecord>(encoded) shouldBe record
    }

    test("RunRecord with Succeeded outcome round-trips") {
        val record = RunRecord(
            taskId = "task_1", startedAtMs = 1L, finishedAtMs = 2L,
            outcome = RunOutcome.Succeeded, summary = "done"
        )
        val encoded = json.encodeToString(record)
        json.decodeFromString<RunRecord>(encoded) shouldBe record
    }

    test("ScheduledTask default id is unique per instance") {
        val a = ScheduledTask(name = "a", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p")
        val b = ScheduledTask(name = "b", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p")
        (a.id != b.id) shouldBe true
    }

    test("ScheduledTask JSON with the old destructiveToolAllowlist key deserializes with toolGrants defaulted to empty") {
        // Exact shape verified empirically pre-rename by encoding a real ScheduledTask
        // (default kotlinx.serialization polymorphic discriminator: key "type", value
        // = fully-qualified subclass name — no @SerialName overrides exist on Trigger/TaskMode).
        // Uses a locally-configured ignoreUnknownKeys=true Json, matching TaskStore's own
        // Json config (TaskStore.kt) — production reads are always tolerant of unknown keys;
        // this file's shared `json` above intentionally isn't, to catch accidental field drift
        // in the round-trip tests.
        val tolerantJson = Json { ignoreUnknownKeys = true }
        val oldFormatJson = """
            {"id":"task_old","name":"legacy","trigger":{"type":"dev.sophi.schedule.model.Trigger.Manual"},
             "mode":{"type":"dev.sophi.schedule.model.TaskMode.Recurring"},"prompt":"p",
             "destructiveToolAllowlist":["fetch_url"],"subagentType":null,"enabled":true,
             "lastRunAtMs":null,"nextRunAtMs":null,"iterationCount":0,"createdAtMs":1000}
        """.trimIndent()
        val decoded = tolerantJson.decodeFromString<ScheduledTask>(oldFormatJson)
        decoded.toolGrants shouldBe emptySet()
    }
})
