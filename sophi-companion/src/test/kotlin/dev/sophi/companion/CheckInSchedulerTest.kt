package dev.sophi.companion

import dev.sophi.schedule.model.CronSchedules
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

class CheckInSchedulerTest : FunSpec({
    test("does not fire before its next cron occurrence arrives") {
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val scheduler = CheckInScheduler(listOf(checkIn))
        val now = System.currentTimeMillis()
        val fireAt = CronSchedules.nextFireTimeAfter(checkIn.cronExpression, now - 1)!!

        scheduler.checkAndFire(now) shouldBe emptyList()
        (fireAt > now) shouldBe true
    }

    test("fires a check-in once nowMs reaches its next cron occurrence") {
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val scheduler = CheckInScheduler(listOf(checkIn))
        val fireAt = CronSchedules.nextFireTimeAfter(checkIn.cronExpression, System.currentTimeMillis() - 1)!!

        scheduler.checkAndFire(fireAt) shouldBe listOf(checkIn)
    }

    test("does not refire the same occurrence on a later tick") {
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val scheduler = CheckInScheduler(listOf(checkIn))
        val fireAt = CronSchedules.nextFireTimeAfter(checkIn.cronExpression, System.currentTimeMillis() - 1)!!

        scheduler.checkAndFire(fireAt) shouldBe listOf(checkIn)
        scheduler.checkAndFire(fireAt + 1000) shouldBe emptyList()
    }

    test("fires again once the next occurrence arrives") {
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val scheduler = CheckInScheduler(listOf(checkIn))
        val firstFireAt = CronSchedules.nextFireTimeAfter(checkIn.cronExpression, System.currentTimeMillis() - 1)!!
        scheduler.checkAndFire(firstFireAt) shouldBe listOf(checkIn)

        val secondFireAt = CronSchedules.nextFireTimeAfter(checkIn.cronExpression, firstFireAt)!!
        scheduler.checkAndFire(secondFireAt) shouldBe listOf(checkIn)
    }

    test("fires even when polling ticks never land exactly on the cron boundary") {
        // Real production polling calls checkAndFire(System.currentTimeMillis()) on a fixed
        // interval unrelated to the cron schedule's phase, so a tick almost never lands exactly
        // on the boundary the way the tests above (which pass fireAt itself) do. This reproduces
        // that shape: one tick before the boundary, one tick some seconds after it.
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val scheduler = CheckInScheduler(listOf(checkIn))
        val fireAt = CronSchedules.nextFireTimeAfter(checkIn.cronExpression, System.currentTimeMillis() - 1)!!

        scheduler.checkAndFire(fireAt - 5_000) shouldBe emptyList()
        scheduler.checkAndFire(fireAt + 23_000) shouldBe listOf(checkIn)
    }

    test("multiple check-ins on the same schedule fire together and are both suppressed afterward") {
        val a = CheckIn("a", "question a?", "0 * * * *")
        val b = CheckIn("b", "question b?", "0 * * * *")
        val scheduler = CheckInScheduler(listOf(a, b))
        val fireAt = CronSchedules.nextFireTimeAfter(a.cronExpression, System.currentTimeMillis() - 1)!!

        scheduler.checkAndFire(fireAt) shouldBe listOf(a, b)
        scheduler.checkAndFire(fireAt + 1000) shouldBe emptyList()
    }

    test("buildCheckInPrompt includes the check-in name and captured text") {
        val prompt = buildCheckInPrompt("work-log", "fixed the flaky test", jiraBaseUrl = null, capturedAt = Instant.now())

        prompt shouldContain "work-log"
        prompt shouldContain "fixed the flaky test"
    }

    test("buildCheckInPrompt includes the Jira base URL when configured") {
        val prompt = buildCheckInPrompt("work-log", "PROJ-123", jiraBaseUrl = "https://jira.example.com", capturedAt = Instant.now())

        prompt shouldContain "https://jira.example.com"
    }

    test("buildCheckInPrompt notes no Jira base URL is configured when it's null") {
        val prompt = buildCheckInPrompt("work-log", "PROJ-123", jiraBaseUrl = null, capturedAt = Instant.now())

        prompt shouldContain "No Jira base URL configured"
    }
})
