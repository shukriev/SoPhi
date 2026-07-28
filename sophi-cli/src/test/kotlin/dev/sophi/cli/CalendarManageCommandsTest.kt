package dev.sophi.cli

import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.model.CalendarEventPatch
import dev.sophi.calendar.model.CalendarInfo
import dev.sophi.calendar.provider.CalendarProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

private class FakeCalendarProvider(
    private val calendars: List<CalendarInfo> = listOf(CalendarInfo("Home", "Home", true))
) : CalendarProvider {
    val events = mutableMapOf<String, CalendarEvent>()

    override fun listCalendars(): List<CalendarInfo> = calendars

    override fun create(event: CalendarEvent): CalendarEvent {
        val id = UUID.randomUUID().toString()
        val stored = event.copy(id = id, calendarId = event.calendarId ?: calendars.first().id)
        events[id] = stored
        return stored
    }

    override fun get(eventId: String, calendarId: String?): CalendarEvent? = events[eventId]

    override fun list(calendarId: String?, rangeStartMs: Long, rangeEndMs: Long): List<CalendarEvent> =
        events.values.filter { it.start < rangeEndMs && it.end > rangeStartMs }

    override fun update(eventId: String, calendarId: String?, patch: CalendarEventPatch): CalendarEvent =
        error("not used by CalendarManageCommandsTest")

    override fun delete(eventId: String, calendarId: String?): Boolean = events.remove(eventId) != null
}

class CalendarManageCommandsTest : FunSpec({
    test("CalendarList renders events whose range overlaps the next N days") {
        val provider = FakeCalendarProvider()
        val now = System.currentTimeMillis()
        provider.create(CalendarEvent(title = "Standup", start = now + 3_600_000, end = now + 5_400_000))
        val out = StringBuilder()

        CalendarList(provider, days = 7, echo = { out.appendLine(it) }).run()

        out.toString() shouldContain "Standup"
    }

    test("CalendarList reports when there are no events in range") {
        val out = StringBuilder()
        CalendarList(FakeCalendarProvider(), days = 7, echo = { out.appendLine(it) }).run()
        out.toString() shouldContain "No events in the next 7 day(s)."
    }

    test("CalendarGet renders a known event; reports failure for an unknown id") {
        val provider = FakeCalendarProvider()
        val created = provider.create(CalendarEvent(title = "Standup", start = 1000L, end = 2000L, location = "Room A"))
        val out = StringBuilder()

        CalendarGet(provider, created.id!!, null, echo = { out.appendLine(it) }).run()
        out.toString() shouldContain "Standup"
        out.toString() shouldContain "Room A"

        val out2 = StringBuilder()
        CalendarGet(provider, "no-such-id", null, echo = { out2.appendLine(it) }).run()
        out2.toString() shouldContain "No event found"
    }

    test("CalendarDelete removes a known event; reports failure for an unknown id") {
        val provider = FakeCalendarProvider()
        val created = provider.create(CalendarEvent(title = "Standup", start = 1000L, end = 2000L))
        val out = StringBuilder()

        CalendarDelete(provider, created.id!!, null, echo = { out.appendLine(it) }).run()
        provider.get(created.id!!, null) shouldBe null
        out.toString() shouldContain "Deleted"

        val out2 = StringBuilder()
        CalendarDelete(provider, "no-such-id", null, echo = { out2.appendLine(it) }).run()
        out2.toString() shouldContain "No event found"
    }

    test("CalendarCalendars renders calendar names, marking the default") {
        val provider = FakeCalendarProvider(listOf(CalendarInfo("Home", "Home", true), CalendarInfo("Work", "Work", false)))
        val out = StringBuilder()

        CalendarCalendars(provider, echo = { out.appendLine(it) }).run()

        out.toString() shouldContain "Home (default)"
        out.toString() shouldContain "Work"
    }
})
