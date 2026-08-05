package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.calendar.tools.CreateCalendarEventTool
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CalendarCreate(
    private val provider: LLMProvider,
    private val calendarProvider: CalendarProvider,
    private val sessionManager: SessionManager,
    private val confirmationPolicy: ConfirmationPolicy,
    private val config: AgentConfig,
    /** Total context window of `config.model`, in tokens — see AgentLoop. */
    private val contextWindowTokens: Int,
    private val description: String,
    private val echo: (String) -> Unit
) {
    suspend fun run() {
        val scopedRegistry = ToolRegistry().register(CreateCalendarEventTool(calendarProvider))
        val nestedLoop = AgentLoop(
            provider, scopedRegistry, sessionManager,
            confirmationPolicy = confirmationPolicy,
            contextWindowTokens = contextWindowTokens
        )
        val now = ZonedDateTime.now()
        val systemPrompt = "You create exactly one calendar event from a short shorthand description using the " +
            "create_calendar_event tool. The current date/time is: epoch_ms=${now.toInstant().toEpochMilli()} " +
            "iso=${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} timezone=${now.zone.id} day_of_week=${now.dayOfWeek}. " +
            "Never call get_current_datetime — it is not available here; use the date/time given above directly. " +
            "For a one-off or timed occasion, use 'start'/'end' as ISO-8601 local date-times. For an all-day or " +
            "date-only occasion (a birthday, an anniversary, a day off), set \"all_day\": true and use " +
            "'start_date'/'end_date' (YYYY-MM-DD, no time-of-day) instead — never invent a time of day for something " +
            "that's naturally all-day. If the description implies yearly/monthly/weekly repetition (\"every year\", " +
            "\"anniversary\", \"birthday\"), set the 'recurrence' field accordingly. Call create_calendar_event exactly " +
            "once, then reply with one short confirmation sentence."
        val subSession = sessionManager.create(title = "calendar-create")
        val result = try {
            nestedLoop.turn(subSession, description, config.copy(systemPrompt = systemPrompt))
        } finally {
            sessionManager.save(subSession)
        }
        echo(result.tip?.content ?: "(no response)")
    }
}
