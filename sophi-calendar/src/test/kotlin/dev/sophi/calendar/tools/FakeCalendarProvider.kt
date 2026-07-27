package dev.sophi.calendar.tools

import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.model.CalendarEventPatch
import dev.sophi.calendar.model.CalendarInfo
import dev.sophi.calendar.provider.CalendarProvider
import java.util.UUID

class FakeCalendarProvider(
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

    override fun update(eventId: String, calendarId: String?, patch: CalendarEventPatch): CalendarEvent {
        val existing = events[eventId] ?: error("No event found with id $eventId")
        val updated = existing.copy(
            title = patch.title ?: existing.title,
            start = patch.start ?: existing.start,
            end = patch.end ?: existing.end,
            allDay = patch.allDay ?: existing.allDay,
            location = patch.location ?: existing.location,
            notes = patch.notes ?: existing.notes,
            reminderMinutesBefore = patch.reminderMinutesBefore ?: existing.reminderMinutesBefore,
            recurrence = if (patch.clearRecurrence) null else patch.recurrence ?: existing.recurrence
        )
        events[eventId] = updated
        return updated
    }

    override fun delete(eventId: String, calendarId: String?): Boolean = events.remove(eventId) != null
}
