package dev.sophi.calendar.tools

import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class DeleteEventArgs(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("calendar_id") val calendarId: String? = null
)

class DeleteCalendarEventTool(private val provider: CalendarProvider) : Tool {
    override val name = "delete_calendar_event"
    override val description = "Delete a calendar event by id"
    override val riskLevel = RiskLevel.DESTRUCTIVE
    override val parametersJson = """
        {"type":"object","properties":{
          "event_id":{"type":"string"},
          "calendar_id":{"type":"string"}
        },"required":["event_id"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(DeleteEventArgs.serializer(), argumentsJson)
        val eventId = args.eventId ?: return "Error: 'event_id' is required"
        return if (provider.delete(eventId, args.calendarId)) {
            "Deleted event $eventId"
        } else {
            "Error: no event found with id $eventId"
        }
    }
}
