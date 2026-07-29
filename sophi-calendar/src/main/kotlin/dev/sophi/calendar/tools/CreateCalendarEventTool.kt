package dev.sophi.calendar.tools

import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.model.Recurrence
import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.tools.RiskLevel
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
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.CAUTION
    override val description = "Create a calendar event (optionally recurring or all-day) on the native OS calendar. " +
        "For a timed event, set 'start'/'end' to ISO-8601 local date-times (e.g. 2026-07-27T12:00:00) — never " +
        "epoch milliseconds. For an all-day event, set \"all_day\": true and use 'start_date'/'end_date' " +
        "(ISO YYYY-MM-DD, date only, no time-of-day). If the request uses a relative date or time " +
        "(\"tomorrow\", \"next Monday\", \"in an hour\"), call get_current_datetime first to establish the " +
        "current date and timezone; never guess it."
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

    private fun invalidDateTimeError(field: String, value: String): String {
        val epochHint = if (value.toLongOrNull() != null) {
            " — this looks like epoch milliseconds, not an ISO-8601 date-time; convert it to e.g. 2026-07-27T12:00:00"
        } else ""
        return "Error: invalid '$field' datetime '$value', expected ISO-8601 like 2026-07-27T12:00:00$epochHint"
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(CreateEventArgs.serializer(), argumentsJson)
        val title = args.title ?: return "Error: 'title' is required"
        var startMs = 0L
        var endMs = 0L
        if (args.allDay) {
            val startDateStr = args.startDate
                ?: return "Error: 'start_date' and 'end_date' are required when all_day=true"
            val endDateStr = args.endDate
                ?: return "Error: 'start_date' and 'end_date' are required when all_day=true"
            runCatching { java.time.LocalDate.parse(startDateStr) }.getOrElse {
                return "Error: invalid 'start_date' '$startDateStr', expected ISO YYYY-MM-DD (date only, no time-of-day)"
            }
            runCatching { java.time.LocalDate.parse(endDateStr) }.getOrElse {
                return "Error: invalid 'end_date' '$endDateStr', expected ISO YYYY-MM-DD (date only, no time-of-day)"
            }
        } else {
            val startStr = args.start
                ?: return "Error: 'start' and 'end' (ISO-8601 date-time) are required unless all_day=true" +
                    if (args.startDate != null || args.endDate != null) {
                        " — you supplied 'start_date'/'end_date' instead; if this should be an all-day event, add \"all_day\": true"
                    } else ""
            val endStr = args.end
                ?: return "Error: 'start' and 'end' (ISO-8601 date-time) are required unless all_day=true" +
                    if (args.startDate != null || args.endDate != null) {
                        " — you supplied 'start_date'/'end_date' instead; if this should be an all-day event, add \"all_day\": true"
                    } else ""
            startMs = parseLocalDateTime(startStr) ?: return invalidDateTimeError("start", startStr)
            endMs = parseLocalDateTime(endStr) ?: return invalidDateTimeError("end", endStr)
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
