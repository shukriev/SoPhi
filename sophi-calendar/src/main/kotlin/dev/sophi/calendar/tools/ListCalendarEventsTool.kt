package dev.sophi.calendar.tools

import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.tools.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ListEventsArgs(
    @SerialName("calendar_id") val calendarId: String? = null,
    @SerialName("range_start_ms") val rangeStartMs: Long? = null,
    @SerialName("range_end_ms") val rangeEndMs: Long? = null
)

class ListCalendarEventsTool(private val provider: CalendarProvider) : Tool {
    override val name = "list_calendar_events"
    override val description = "List calendar events overlapping a time range"
    override val parametersJson = """
        {"type":"object","properties":{
          "calendar_id":{"type":"string","description":"Defaults to the OS default calendar"},
          "range_start_ms":{"type":"integer","description":"Epoch millis, inclusive"},
          "range_end_ms":{"type":"integer","description":"Epoch millis, exclusive"}
        },"required":["range_start_ms","range_end_ms"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(ListEventsArgs.serializer(), argumentsJson)
        val start = args.rangeStartMs ?: return "Error: 'range_start_ms' is required"
        val end = args.rangeEndMs ?: return "Error: 'range_end_ms' is required"
        val events = provider.list(args.calendarId, start, end)
        if (events.isEmpty()) return "No events in range."
        return events.joinToString("\n") { "${it.id}  ${it.title}  ${it.start}-${it.end}" }
    }
}
