package dev.sophi.calendar.tools

import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.model.Recurrence
import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.tools.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId

@Serializable
private data class RecurrenceArgs(
    val frequency: String,
    val interval: Int = 1,
    val count: Int? = null,
    val until: Long? = null,
    @SerialName("by_weekday") val byWeekday: List<String>? = null
)

@Serializable
private data class CreateEventArgs(
    val title: String? = null,
    @SerialName("calendar_id") val calendarId: String? = null,
    val start: String? = null,
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val location: String? = null,
    val notes: String? = null,
    @SerialName("reminder_minutes_before") val reminderMinutesBefore: Int? = null,
    val recurrence: RecurrenceArgs? = null
)

private fun RecurrenceArgs.toModel(): Recurrence = Recurrence(
    frequency = dev.sophi.calendar.model.Frequency.valueOf(frequency),
    interval = interval,
    count = count,
    until = until,
    byWeekday = byWeekday?.map { java.time.DayOfWeek.valueOf(it) }
)

class CreateCalendarEventTool(private val provider: CalendarProvider) : Tool {
    override val name = "create_calendar_event"
    override val description = "Create a calendar event (optionally recurring or all-day) on the native OS calendar. " +
        "start/end (or start_date/end_date for all-day) are epoch millis / ISO dates you must compute yourself — " +
        "if the request uses a relative date or time (\"tomorrow\", \"next Monday\", \"in an hour\"), call " +
        "get_current_datetime first to establish the current date and timezone; never guess it."
    override val parametersJson = """
        {"type":"object","properties":{
          "title":{"type":"string"},
          "calendar_id":{"type":"string","description":"Defaults to the OS default calendar"},
          "start":{"type":"string","description":"ISO-8601 local date-time without a UTC offset (e.g. 2026-07-27T12:00:00) — interpreted in the OS's local timezone, matching get_current_datetime's zone; required unless all_day=true"},
          "end":{"type":"string","description":"ISO-8601 local date-time, same format as start; required unless all_day=true"},
          "all_day":{"type":"boolean","default":false},
          "start_date":{"type":"string","description":"ISO YYYY-MM-DD; required when all_day=true"},
          "end_date":{"type":"string","description":"ISO YYYY-MM-DD; required when all_day=true"},
          "location":{"type":"string"},
          "notes":{"type":"string"},
          "reminder_minutes_before":{"type":"integer"},
          "recurrence":{"type":"object","properties":{
            "frequency":{"type":"string","enum":["DAILY","WEEKLY","MONTHLY","YEARLY"]},
            "interval":{"type":"integer","default":1},
            "count":{"type":"integer"},
            "until":{"type":"integer","description":"Epoch millis"},
            "by_weekday":{"type":"array","items":{"type":"string","enum":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"]}}
          },"required":["frequency"]}
        },"required":["title"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseLocalDateTime(s: String): Long? =
        runCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(CreateEventArgs.serializer(), argumentsJson)
        val title = args.title ?: return "Error: 'title' is required"
        var startMs = 0L
        var endMs = 0L
        if (args.allDay) {
            if (args.startDate == null || args.endDate == null) {
                return "Error: 'start_date' and 'end_date' are required when all_day=true"
            }
        } else {
            val startStr = args.start ?: return "Error: 'start' and 'end' are required unless all_day=true"
            val endStr = args.end ?: return "Error: 'start' and 'end' are required unless all_day=true"
            startMs = parseLocalDateTime(startStr)
                ?: return "Error: invalid 'start' datetime '$startStr', expected ISO-8601 like 2026-07-27T12:00:00"
            endMs = parseLocalDateTime(endStr)
                ?: return "Error: invalid 'end' datetime '$endStr', expected ISO-8601 like 2026-07-27T12:00:00"
        }

        val event = CalendarEvent(
            calendarId = args.calendarId,
            title = title,
            start = startMs,
            end = endMs,
            allDay = args.allDay,
            startDate = args.startDate,
            endDate = args.endDate,
            location = args.location,
            notes = args.notes,
            reminderMinutesBefore = args.reminderMinutesBefore,
            recurrence = args.recurrence?.toModel()
        )
        val created = provider.create(event)
        return "Created event ${created.id} (${created.title}) on calendar ${created.calendarId}"
    }
}
