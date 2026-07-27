package dev.sophi.calendar.tools

import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.tools.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId

@Serializable
private data class ListEventsArgs(
    @SerialName("calendar_id") val calendarId: String? = null,
    @SerialName("range_start") val rangeStart: String? = null,
    @SerialName("range_end") val rangeEnd: String? = null
)

class ListCalendarEventsTool(private val provider: CalendarProvider) : Tool {
    override val name = "list_calendar_events"
    override val description = "List calendar events overlapping a time range. range_start/range_end are ISO-8601 " +
        "local date-times you must compute yourself — if the request uses a relative range (\"today\", \"this week\"), " +
        "call get_current_datetime first to establish the current date and timezone; never guess it."
    override val parametersJson = """
        {"type":"object","properties":{
          "calendar_id":{"type":"string","description":"Defaults to the OS default calendar"},
          "range_start":{"type":"string","description":"ISO-8601 local date-time, inclusive, e.g. 2026-07-27T00:00:00"},
          "range_end":{"type":"string","description":"ISO-8601 local date-time, exclusive, same format as range_start"}
        },"required":["range_start","range_end"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseLocalDateTime(s: String): Long? =
        runCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(ListEventsArgs.serializer(), argumentsJson)
        val startStr = args.rangeStart ?: return "Error: 'range_start' is required"
        val endStr = args.rangeEnd ?: return "Error: 'range_end' is required"
        val start = parseLocalDateTime(startStr)
            ?: return "Error: invalid 'range_start' datetime '$startStr', expected ISO-8601 like 2026-07-27T00:00:00"
        val end = parseLocalDateTime(endStr)
            ?: return "Error: invalid 'range_end' datetime '$endStr', expected ISO-8601 like 2026-07-27T00:00:00"
        val events = provider.list(args.calendarId, start, end)
        if (events.isEmpty()) return "No events in range."
        return events.joinToString("\n") { "${it.id}  ${it.title}  ${it.start}-${it.end}" }
    }
}
