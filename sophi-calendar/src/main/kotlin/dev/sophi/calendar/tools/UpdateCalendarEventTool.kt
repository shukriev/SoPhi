package dev.sophi.calendar.tools

import dev.sophi.calendar.model.CalendarEventPatch
import dev.sophi.calendar.model.Recurrence
import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class UpdateRecurrenceArgs(
    val frequency: String,
    val interval: Int = 1,
    val count: Int? = null,
    val until: Long? = null,
    @SerialName("by_weekday") val byWeekday: List<String>? = null
)

@Serializable
private data class UpdateEventArgs(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("calendar_id") val calendarId: String? = null,
    val title: String? = null,
    val start: Long? = null,
    val end: Long? = null,
    @SerialName("all_day") val allDay: Boolean? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val location: String? = null,
    val notes: String? = null,
    @SerialName("reminder_minutes_before") val reminderMinutesBefore: Int? = null,
    val recurrence: UpdateRecurrenceArgs? = null,
    @SerialName("clear_recurrence") val clearRecurrence: Boolean = false
)

private fun UpdateRecurrenceArgs.toModel(): Recurrence = Recurrence(
    frequency = dev.sophi.calendar.model.Frequency.valueOf(frequency),
    interval = interval,
    count = count,
    until = until,
    byWeekday = byWeekday?.map { java.time.DayOfWeek.valueOf(it) }
)

class UpdateCalendarEventTool(private val provider: CalendarProvider) : Tool {
    override val name = "update_calendar_event"
    override val description = "Update fields on an existing calendar event; omitted fields are left unchanged"
    override val riskLevel = RiskLevel.DESTRUCTIVE
    override val parametersJson = """
        {"type":"object","properties":{
          "event_id":{"type":"string"},
          "calendar_id":{"type":"string"},
          "title":{"type":"string"},
          "start":{"type":"integer","description":"Epoch millis"},
          "end":{"type":"integer","description":"Epoch millis"},
          "all_day":{"type":"boolean"},
          "start_date":{"type":"string"},
          "end_date":{"type":"string"},
          "location":{"type":"string"},
          "notes":{"type":"string"},
          "reminder_minutes_before":{"type":"integer"},
          "recurrence":{"type":"object","properties":{
            "frequency":{"type":"string","enum":["DAILY","WEEKLY","MONTHLY","YEARLY"]},
            "interval":{"type":"integer","default":1},
            "count":{"type":"integer"},
            "until":{"type":"integer"},
            "by_weekday":{"type":"array","items":{"type":"string","enum":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"]}}
          },"required":["frequency"]},
          "clear_recurrence":{"type":"boolean","default":false,"description":"Set true to remove an existing recurrence rather than leaving it unchanged"}
        },"required":["event_id"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(UpdateEventArgs.serializer(), argumentsJson)
        val eventId = args.eventId ?: return "Error: 'event_id' is required"
        val patch = CalendarEventPatch(
            title = args.title,
            start = args.start,
            end = args.end,
            allDay = args.allDay,
            startDate = args.startDate,
            endDate = args.endDate,
            location = args.location,
            notes = args.notes,
            reminderMinutesBefore = args.reminderMinutesBefore,
            recurrence = args.recurrence?.toModel(),
            clearRecurrence = args.clearRecurrence
        )
        val updated = runCatching { provider.update(eventId, args.calendarId, patch) }
            .getOrElse { return "Error: ${it.message}" }
        return "Updated event ${updated.id} (${updated.title})"
    }
}
