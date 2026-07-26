package dev.sophi.calendar.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking

class ListCalendarEventsToolTest : FunSpec({
    test("riskLevel is SAFE") {
        ListCalendarEventsTool(FakeCalendarProvider()).riskLevel shouldBe dev.sophi.core.tools.RiskLevel.SAFE
    }

    test("list returns matching events within the range") {
        val provider = FakeCalendarProvider()
        provider.create(dev.sophi.calendar.model.CalendarEvent(title = "In range", start = 100L, end = 200L))
        provider.create(dev.sophi.calendar.model.CalendarEvent(title = "Out of range", start = 900L, end = 1000L))
        val result = runBlocking {
            ListCalendarEventsTool(provider).execute("""{"range_start_ms":0,"range_end_ms":500}""")
        }
        result shouldContain "In range"
        result shouldNotContain "Out of range"
    }

    test("list with no matches returns a 'No events' message") {
        val result = runBlocking {
            ListCalendarEventsTool(FakeCalendarProvider()).execute("""{"range_start_ms":0,"range_end_ms":500}""")
        }
        result shouldContain "No events"
    }

    test("list without required range args returns an Error string") {
        val result = runBlocking { ListCalendarEventsTool(FakeCalendarProvider()).execute("""{}""") }
        result shouldContain "Error"
    }
})
