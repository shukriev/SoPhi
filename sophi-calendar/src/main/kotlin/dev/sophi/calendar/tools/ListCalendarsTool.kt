package dev.sophi.calendar.tools

import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.tools.Tool

class ListCalendarsTool(private val provider: CalendarProvider) : Tool {
    override val name = "list_calendars"
    override val description = "List the calendars available on this OS"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override suspend fun execute(argumentsJson: String): String {
        val calendars = provider.listCalendars()
        if (calendars.isEmpty()) return "No calendars found."
        return calendars.joinToString("\n") { "${it.id}  ${it.name}${if (it.isDefault) "  (default)" else ""}" }
    }
}
