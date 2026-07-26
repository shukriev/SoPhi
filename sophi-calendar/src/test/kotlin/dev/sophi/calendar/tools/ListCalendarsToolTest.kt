package dev.sophi.calendar.tools

import dev.sophi.calendar.model.CalendarInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class ListCalendarsToolTest : FunSpec({
    test("riskLevel is SAFE") {
        ListCalendarsTool(FakeCalendarProvider()).riskLevel shouldBe dev.sophi.core.tools.RiskLevel.SAFE
    }

    test("lists calendar names and marks the default one") {
        val provider = FakeCalendarProvider(
            calendars = listOf(CalendarInfo("Home", "Home", true), CalendarInfo("Work", "Work", false))
        )
        val result = runBlocking { ListCalendarsTool(provider).execute("{}") }
        result shouldContain "Home"
        result shouldContain "Work"
        result shouldContain "default"
    }
})
