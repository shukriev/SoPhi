package dev.sophi.cli

import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.calendar.provider.MacCalendarProvider
import dev.sophi.calendar.provider.UnsupportedCalendarProvider

internal fun buildCalendarProvider(): CalendarProvider =
    if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true) {
        MacCalendarProvider()
    } else {
        UnsupportedCalendarProvider
    }
