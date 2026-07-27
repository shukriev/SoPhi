package dev.sophi.calendar.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId

private fun epochMs(iso: String): Long =
    LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

class ListCalendarEventsToolTest : FunSpec({
    test("riskLevel is SAFE") {
        ListCalendarEventsTool(FakeCalendarProvider()).riskLevel("{}") shouldBe dev.sophi.core.tools.RiskLevel.SAFE
    }

    test("list returns matching events within the range") {
        val provider = FakeCalendarProvider()
        provider.create(
            dev.sophi.calendar.model.CalendarEvent(
                title = "In range", start = epochMs("2026-08-01T09:00:00"), end = epochMs("2026-08-01T10:00:00")
            )
        )
        provider.create(
            dev.sophi.calendar.model.CalendarEvent(
                title = "Out of range", start = epochMs("2026-08-05T09:00:00"), end = epochMs("2026-08-05T10:00:00")
            )
        )
        val result = runBlocking {
            ListCalendarEventsTool(provider).execute("""{"range_start":"2026-08-01T00:00:00","range_end":"2026-08-02T00:00:00"}""")
        }
        result shouldContain "In range"
        result shouldNotContain "Out of range"
    }

    test("list with no matches returns a 'No events' message") {
        val result = runBlocking {
            ListCalendarEventsTool(FakeCalendarProvider()).execute("""{"range_start":"2026-08-01T00:00:00","range_end":"2026-08-02T00:00:00"}""")
        }
        result shouldContain "No events"
    }

    test("list without required range args returns an Error string") {
        val result = runBlocking { ListCalendarEventsTool(FakeCalendarProvider()).execute("""{}""") }
        result shouldContain "Error"
    }

    test("list with an invalid range_start returns an Error string") {
        val result = runBlocking {
            ListCalendarEventsTool(FakeCalendarProvider()).execute(
                """{"range_start":"nonsense","range_end":"2026-08-02T00:00:00"}"""
            )
        }
        result shouldContain "Error"
    }
})
