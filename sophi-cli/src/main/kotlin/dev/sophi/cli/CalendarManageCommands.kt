package dev.sophi.cli

import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.provider.CalendarProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

private fun renderRange(e: CalendarEvent): String =
    if (e.allDay) "${e.startDate}..${e.endDate}"
    else "${formatMs(e.start)}..${formatMs(e.end)}"

private fun formatMs(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DISPLAY_FORMAT)

class CalendarList(
    private val provider: CalendarProvider,
    private val days: Int,
    private val echo: (String) -> Unit
) {
    fun run() {
        val now = System.currentTimeMillis()
        val events = provider.list(null, now, now + days * 86_400_000L)
        if (events.isEmpty()) { echo("No events in the next $days day(s)."); return }
        events.forEach { echo("${it.id}  ${it.title}  ${renderRange(it)}") }
    }
}

class CalendarGet(
    private val provider: CalendarProvider,
    private val eventId: String,
    private val calendarId: String?,
    private val echo: (String) -> Unit
) {
    fun run() {
        val event = provider.get(eventId, calendarId)
        if (event == null) { echo("No event found with id $eventId"); return }
        echo("${event.id}  ${event.title}  ${renderRange(event)}  calendar=${event.calendarId}")
        event.location?.let { echo("  location: $it") }
        event.notes?.let { echo("  notes: $it") }
    }
}

class CalendarDelete(
    private val provider: CalendarProvider,
    private val eventId: String,
    private val calendarId: String?,
    private val echo: (String) -> Unit
) {
    fun run() {
        if (provider.delete(eventId, calendarId)) echo("Deleted $eventId")
        else echo("No event found with id $eventId")
    }
}

class CalendarCalendars(private val provider: CalendarProvider, private val echo: (String) -> Unit) {
    fun run() {
        val calendars = provider.listCalendars()
        if (calendars.isEmpty()) { echo("No calendars found."); return }
        calendars.forEach { echo("${it.name}${if (it.isDefault) " (default)" else ""}") }
    }
}
