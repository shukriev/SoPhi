package dev.sophi.calendar.tools

import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.tools.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GetEventArgs(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("calendar_id") val calendarId: String? = null
)

class GetCalendarEventTool(private val provider: CalendarProvider) : Tool {
    override val name = "get_calendar_event"
    override val description = "Get a single calendar event by id"
    override val parametersJson = """
        {"type":"object","properties":{
          "event_id":{"type":"string"},
          "calendar_id":{"type":"string","description":"Defaults to the OS default calendar"}
        },"required":["event_id"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(GetEventArgs.serializer(), argumentsJson)
        val eventId = args.eventId ?: return "Error: 'event_id' is required"
        val event = provider.get(eventId, args.calendarId) ?: return "Error: no event found with id $eventId"
        return "${event.id}  ${event.title}  ${event.start}-${event.end}" +
            (event.location?.let { "  location=$it" } ?: "") +
            (event.notes?.let { "  notes=$it" } ?: "")
    }
}
