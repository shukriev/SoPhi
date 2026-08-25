package dev.sophi.calendar.tools

import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.calendar.provider.MacCalendarProvider
import dev.sophi.calendar.provider.UnsupportedCalendarProvider
import dev.sophi.core.tools.Tool

/**
 * macOS (AppleScript/`Calendar.app`) only — [UnsupportedCalendarProvider] on every other OS, so a
 * host that registers [calendarTools] against it fails loudly per call rather than silently.
 */
fun buildCalendarProvider(): CalendarProvider =
    if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true) {
        MacCalendarProvider()
    } else {
        UnsupportedCalendarProvider
    }

/** The six calendar Tools, all bound to [provider]. */
fun calendarTools(provider: CalendarProvider): List<Tool> = listOf(
    CreateCalendarEventTool(provider),
    ListCalendarEventsTool(provider),
    GetCalendarEventTool(provider),
    UpdateCalendarEventTool(provider),
    DeleteCalendarEventTool(provider),
    ListCalendarsTool(provider)
)
