package dev.sophi.calendar.tools

import dev.sophi.calendar.provider.MacCalendarProvider
import dev.sophi.calendar.provider.UnsupportedCalendarProvider
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

    test("buildCalendarProvider() returns MacCalendarProvider on macOS") {
        // This process is macOS-only per this repo's dev/CI environment; a genuine cross-platform
        // check would need to fake System.getProperty, which buildCalendarProvider() doesn't take
        // as a parameter (matching its original sophi-cli shape) — covered instead by asserting
        // the actual runtime behavior here, and UnsupportedCalendarProvider's own isolated
        // behavior in UnsupportedCalendarProviderTest.
        if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true) {
            (buildCalendarProvider() is MacCalendarProvider) shouldBe true
        } else {
            (buildCalendarProvider() is UnsupportedCalendarProvider) shouldBe true
        }
    }
})
