package dev.sophi.calendar.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CalendarToolsTest : FunSpec({
    test("calendarTools() returns exactly the six calendar tools, bound to the given provider") {
        val provider = FakeCalendarProvider()

        val names = calendarTools(provider).map { it.name }.toSet()

        names shouldBe setOf(
            "create_calendar_event", "list_calendar_events", "get_calendar_event",
            "update_calendar_event", "delete_calendar_event", "list_calendars"
        )
    }
})
