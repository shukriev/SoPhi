package dev.sophi.core.tools

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class GetCurrentDateTimeTool(
    private val clock: Clock = Clock.systemDefaultZone()
) : Tool {
    override val name = "get_current_datetime"
    override val description =
        "Get the current date, time, and timezone. Call this before computing epoch-millis " +
            "values for a relative date or time (e.g. \"tomorrow\", \"next Monday\", \"today\") " +
            "— never guess the current date."
    override val parametersJson = """{"type":"object","properties":{}}"""

    override suspend fun execute(argumentsJson: String): String {
        val now = ZonedDateTime.now(clock)
        return "epoch_ms=${now.toInstant().toEpochMilli()} " +
            "iso=${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} " +
            "timezone=${clock.zone.id} " +
            "day_of_week=${now.dayOfWeek}"
    }
}
