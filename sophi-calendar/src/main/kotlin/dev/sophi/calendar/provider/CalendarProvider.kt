package dev.sophi.calendar.provider

import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.model.CalendarEventPatch
import dev.sophi.calendar.model.CalendarInfo

interface CalendarProvider {
    fun listCalendars(): List<CalendarInfo>
    fun create(event: CalendarEvent): CalendarEvent
    fun get(eventId: String, calendarId: String?): CalendarEvent?
    fun list(calendarId: String?, rangeStartMs: Long, rangeEndMs: Long): List<CalendarEvent>
    fun update(eventId: String, calendarId: String?, patch: CalendarEventPatch): CalendarEvent
    fun delete(eventId: String, calendarId: String?): Boolean
}
