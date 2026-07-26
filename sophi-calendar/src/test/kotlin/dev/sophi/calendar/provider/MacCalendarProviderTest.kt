package dev.sophi.calendar.provider

import dev.sophi.calendar.model.CalendarInfo
import dev.sophi.calendar.model.Frequency
import dev.sophi.calendar.model.Recurrence
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.DayOfWeek

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
})
