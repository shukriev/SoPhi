package dev.sophi.calendar.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek

class CalendarEventTest : FunSpec({
    test("CalendarEvent defaults calendarId, recurrence, and id to null") {
        val event = CalendarEvent(title = "Standup", start = 1L, end = 2L)
        event.id.shouldBeNull()
        event.calendarId.shouldBeNull()
        event.recurrence.shouldBeNull()
        event.allDay shouldBe false
    }

    test("Recurrence defaults interval to 1 and leaves count/until/byWeekday null") {
        val r = Recurrence(frequency = Frequency.WEEKLY)
        r.interval shouldBe 1
        r.count.shouldBeNull()
        r.until.shouldBeNull()
        r.byWeekday.shouldBeNull()
    }

    test("Recurrence carries an explicit byWeekday list when provided") {
        val r = Recurrence(frequency = Frequency.WEEKLY, byWeekday = listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        r.byWeekday shouldBe listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
    }
})
