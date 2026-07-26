package dev.sophi.calendar.provider

import dev.sophi.calendar.model.CalendarInfo
import dev.sophi.calendar.model.Frequency
import dev.sophi.calendar.model.Recurrence
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.DayOfWeek

private const val FIELD_SEP_FOR_TEST = "::SOPHI_FIELD::"

class MacCalendarProviderTest : FunSpec({
    test("listCalendars parses one calendar name per line, first line is default") {
        val provider = MacCalendarProvider(runScript = { "Home\nWork\n" })
        provider.listCalendars() shouldBe listOf(
            CalendarInfo(id = "Home", name = "Home", isDefault = true),
            CalendarInfo(id = "Work", name = "Work", isDefault = false)
        )
    }

    test("listCalendars returns an empty list when Calendar.app has no calendars") {
        val provider = MacCalendarProvider(runScript = { "" })
        provider.listCalendars() shouldBe emptyList()
    }

    test("listCalendars script queries every calendar's name") {
        var captured = ""
        val provider = MacCalendarProvider(runScript = { script -> captured = script; "Home\n" })
        provider.listCalendars()
        captured shouldContain "repeat with c in calendars"
        captured shouldContain "name of c"
    }

    test("weekly recurrence with interval and count translates to a minimal RRULE") {
        val rrule = MacCalendarProvider.toRRuleForTest(Recurrence(frequency = Frequency.WEEKLY, interval = 2, count = 5))
        rrule shouldBe "FREQ=WEEKLY;INTERVAL=2;COUNT=5"
    }

    test("recurrence with an until date translates to an UNTIL in UTC basic format") {
        // 2026-08-01T00:00:00Z
        val untilMs = 1785715200000L
        val rrule = MacCalendarProvider.toRRuleForTest(Recurrence(frequency = Frequency.DAILY, until = untilMs))
        rrule shouldContain "FREQ=DAILY;INTERVAL=1;UNTIL="
    }

    test("weekly recurrence with byWeekday translates to a BYDAY list") {
        val rrule = MacCalendarProvider.toRRuleForTest(
            Recurrence(frequency = Frequency.WEEKLY, byWeekday = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        )
        rrule shouldContain "BYDAY=MO,WE,FR"
    }

    test("create builds an AppleScript that sets summary, dates, and returns the uid") {
        var captured = ""
        val provider = MacCalendarProvider(runScript = { script -> captured = script; "ABC-123" })
        val result = provider.create(
            dev.sophi.calendar.model.CalendarEvent(
                calendarId = "Home", title = "Standup", start = 1785700000000L, end = 1785703600000L
            )
        )
        result.id shouldBe "ABC-123"
        result.calendarId shouldBe "Home"
        captured shouldContain "make new event"
        captured shouldContain "summary:\"Standup\""
        captured shouldContain "tell calendar \"Home\""
    }

    test("create resolves a null calendarId to the default calendar") {
        val provider = MacCalendarProvider(runScript = { script ->
            if (script.contains("repeat with c in calendars")) "Home\n" else "XYZ"
        })
        val result = provider.create(dev.sophi.calendar.model.CalendarEvent(title = "x", start = 1L, end = 2L))
        result.calendarId shouldBe "Home"
    }

    test("create with a recurrence sets the recurrence property to an RRULE string") {
        var captured = ""
        val provider = MacCalendarProvider(runScript = { script -> captured = script; "ID1" })
        provider.create(
            dev.sophi.calendar.model.CalendarEvent(
                calendarId = "Home", title = "Standup", start = 1L, end = 2L,
                recurrence = dev.sophi.calendar.model.Recurrence(frequency = dev.sophi.calendar.model.Frequency.DAILY, count = 10)
            )
        )
        captured shouldContain "set recurrence of newEvent to \"FREQ=DAILY;INTERVAL=1;COUNT=10\""
    }

    test("create with a reminder adds a display alarm with a negative trigger interval") {
        var captured = ""
        val provider = MacCalendarProvider(runScript = { script -> captured = script; "ID1" })
        provider.create(
            dev.sophi.calendar.model.CalendarEvent(
                calendarId = "Home", title = "Standup", start = 1L, end = 2L, reminderMinutesBefore = 15
            )
        )
        captured shouldContain "trigger interval:-15"
    }

    test("create for an all-day event uses startDate/endDate and sets allday event to true") {
        var captured = ""
        val provider = MacCalendarProvider(runScript = { script -> captured = script; "ID1" })
        provider.create(
            dev.sophi.calendar.model.CalendarEvent(
                calendarId = "Home", title = "Vacation", allDay = true, startDate = "2026-08-01", endDate = "2026-08-05"
            )
        )
        captured shouldContain "allday event:true"
    }

    test("get parses a formatted event line into a CalendarEvent") {
        // 2026-08-01 15:00:00 local -> 2026-08-01 16:00:00 local
        val line = listOf(
            "ABC", "Standup", "2026", "8", "1", "54000", "2026", "8", "1", "57600", "false", "Room 1", "Notes"
        ).joinToString(FIELD_SEP_FOR_TEST)
        val provider = MacCalendarProvider(runScript = { line })
        val event = provider.get("ABC", "Home")
        event.shouldNotBeNull()
        event!!.id shouldBe "ABC"
        event.title shouldBe "Standup"
        event.location shouldBe "Room 1"
        event.notes shouldBe "Notes"
        event.calendarId shouldBe "Home"
    }

    test("get returns null when Calendar.app reports NOT_FOUND") {
        val provider = MacCalendarProvider(runScript = { "NOT_FOUND" })
        provider.get("missing", "Home") shouldBe null
    }

    test("list parses multiple event lines, one per line") {
        val line1 = listOf("ID1", "A", "2026", "8", "1", "0", "2026", "8", "1", "3600", "false", "", "").joinToString(FIELD_SEP_FOR_TEST)
        val line2 = listOf("ID2", "B", "2026", "8", "2", "0", "2026", "8", "2", "3600", "false", "", "").joinToString(FIELD_SEP_FOR_TEST)
        val provider = MacCalendarProvider(runScript = { "$line1\n$line2" })
        val events = provider.list("Home", 0L, Long.MAX_VALUE)
        events.map { it.id } shouldBe listOf("ID1", "ID2")
        events.map { it.title } shouldBe listOf("A", "B")
    }

    test("list returns an empty list when Calendar.app reports no matches") {
        val provider = MacCalendarProvider(runScript = { "" })
        provider.list("Home", 0L, 1L) shouldBe emptyList()
    }

    test("list script uses an overlap filter, not a starts-within filter") {
        var captured = ""
        val provider = MacCalendarProvider(runScript = { script -> captured = script; "" })
        provider.list("Home", 0L, 1L)
        captured shouldContain "start date < rangeEnd and end date > rangeStart"
    }

    test("update with only a title patch sets summary and leaves other fields alone") {
        var captured = ""
        val resultLine = listOf("ID1", "New Title", "2026", "8", "1", "0", "2026", "8", "1", "3600", "false", "", "").joinToString(FIELD_SEP_FOR_TEST)
        val provider = MacCalendarProvider(runScript = { script -> captured = script; resultLine })
        val updated = provider.update("ID1", "Home", dev.sophi.calendar.model.CalendarEventPatch(title = "New Title"))
        updated.title shouldBe "New Title"
        captured shouldContain "set summary of theEvent to \"New Title\""
        captured shouldNotContain "set location of theEvent"
    }

    test("update throws when the event id doesn't exist") {
        val provider = MacCalendarProvider(runScript = { "NOT_FOUND" })
        val ex = runCatching {
            provider.update("missing", "Home", dev.sophi.calendar.model.CalendarEventPatch(title = "x"))
        }.exceptionOrNull()
        ex?.message shouldContain "missing"
    }

    test("update with clearRecurrence=true sets recurrence to an empty string") {
        var captured = ""
        val resultLine = listOf("ID1", "T", "2026", "8", "1", "0", "2026", "8", "1", "3600", "false", "", "").joinToString(FIELD_SEP_FOR_TEST)
        val provider = MacCalendarProvider(runScript = { script -> captured = script; resultLine })
        provider.update("ID1", "Home", dev.sophi.calendar.model.CalendarEventPatch(clearRecurrence = true))
        captured shouldContain "set recurrence of theEvent to \"\""
    }

    test("delete removes the event and returns true") {
        val provider = MacCalendarProvider(runScript = { "DELETED" })
        provider.delete("ID1", "Home") shouldBe true
    }

    test("delete returns false when the event id doesn't exist") {
        val provider = MacCalendarProvider(runScript = { "NOT_FOUND" })
        provider.delete("missing", "Home") shouldBe false
    }
})
