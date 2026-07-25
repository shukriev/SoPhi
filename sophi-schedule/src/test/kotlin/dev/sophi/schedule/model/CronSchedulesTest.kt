package dev.sophi.schedule.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class CronSchedulesTest : FunSpec({
    test("validate accepts a daily expression") {
        CronSchedules.validate("0 9 * * *").shouldBeNull()
    }

    test("validate accepts an hourly expression") {
        CronSchedules.validate("0 * * * *").shouldBeNull()
    }

    test("validate accepts a weekly expression") {
        CronSchedules.validate("0 12 * * 1").shouldBeNull()
    }

    test("validate rejects a malformed expression") {
        CronSchedules.validate("not a cron").shouldNotBeNull()
    }

    test("nextFireTimeAfter returns a timestamp within the next day for a daily expression") {
        val afterMs = System.currentTimeMillis()
        val next = CronSchedules.nextFireTimeAfter("0 9 * * *", afterMs)
        next.shouldNotBeNull()
        (next!! > afterMs) shouldBe true
        (next < afterMs + 25 * 60 * 60 * 1000) shouldBe true
    }
})
