package dev.sophi.calendar.provider

import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.model.CalendarEventPatch
import dev.sophi.calendar.model.CalendarInfo

object UnsupportedCalendarProvider : CalendarProvider {
    private fun unsupported(): Nothing = error("No calendar backend available on this OS")

    override fun listCalendars(): List<CalendarInfo> = unsupported()
    override fun create(event: CalendarEvent): CalendarEvent = unsupported()
    override fun get(eventId: String, calendarId: String?): CalendarEvent? = unsupported()
    override fun list(calendarId: String?, rangeStartMs: Long, rangeEndMs: Long): List<CalendarEvent> = unsupported()
    override fun update(eventId: String, calendarId: String?, patch: CalendarEventPatch): CalendarEvent = unsupported()
    override fun delete(eventId: String, calendarId: String?): Boolean = unsupported()
}
