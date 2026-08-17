package dev.sophi.cli

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.model.CalendarEventPatch
import dev.sophi.calendar.model.CalendarInfo
import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import kotlin.io.path.createTempDirectory

private class FakeCreateOnlyCalendarProvider : CalendarProvider {
    val created = mutableListOf<CalendarEvent>()
    override fun listCalendars(): List<CalendarInfo> = listOf(CalendarInfo("Home", "Home", true))
    override fun create(event: CalendarEvent): CalendarEvent {
        val stored = event.copy(id = UUID.randomUUID().toString(), calendarId = "Home")
        created += stored
        return stored
    }
    override fun get(eventId: String, calendarId: String?): CalendarEvent? = null
    override fun list(calendarId: String?, rangeStartMs: Long, rangeEndMs: Long): List<CalendarEvent> = emptyList()
    override fun update(eventId: String, calendarId: String?, patch: CalendarEventPatch): CalendarEvent =
        error("not used by CalendarCreateCommandTest")
    override fun delete(eventId: String, calendarId: String?): Boolean = false
}

private const val TEST_CONTEXT_WINDOW = 100_000

class CalendarCreateCommandTest : FunSpec({
    val config = AgentConfig(model = "test-model")

    test("run() scopes the nested loop to exactly the create_calendar_event tool") {
        val provider = mockk<LLMProvider>()
        val capturedRequests = mutableListOf<CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            flowOf(StreamEvent.Content("done"))
        }
        val sessionManager = FileSessionManager(createTempDirectory("calendar-create-test"))

        CalendarCreate(
            provider, FakeCreateOnlyCalendarProvider(), sessionManager, ConfirmationPolicy.ALLOW_ALL, config,
            TEST_CONTEXT_WINDOW, "Anniversary 2026-09-24"
        ) {}.run()

        capturedRequests.single().tools.map { it.name } shouldBe listOf("create_calendar_event")
    }

    test("run() embeds the current date/time in the system prompt and tells the model not to call get_current_datetime") {
        val provider = mockk<LLMProvider>()
        val capturedRequests = mutableListOf<CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            flowOf(StreamEvent.Content("done"))
        }
        val sessionManager = FileSessionManager(createTempDirectory("calendar-create-test"))

        CalendarCreate(
            provider, FakeCreateOnlyCalendarProvider(), sessionManager, ConfirmationPolicy.ALLOW_ALL, config,
            TEST_CONTEXT_WINDOW, "Anniversary 2026-09-24"
        ) {}.run()

        val prompt = capturedRequests.single().systemPrompt!!
        prompt shouldContain "epoch_ms="
        prompt shouldContain "Never call get_current_datetime"
    }

    test("run() echoes the model's final text response") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("Created your anniversary event!"))
        val sessionManager = FileSessionManager(createTempDirectory("calendar-create-test"))
        val echoed = mutableListOf<String>()

        CalendarCreate(
            provider, FakeCreateOnlyCalendarProvider(), sessionManager, ConfirmationPolicy.ALLOW_ALL, config,
            TEST_CONTEXT_WINDOW, "Anniversary 2026-09-24"
        ) { echoed.add(it) }.run()

        echoed shouldBe listOf("Created your anniversary event!")
    }

    test("run() denies the tool call under DENY_ALL, and no event is created") {
        val provider = mockk<LLMProvider>()
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1)
                flowOf(StreamEvent.ToolCallsReady(listOf(
                    ToolCall(
                        "c1", "create_calendar_event",
                        """{"title":"Anniversary","all_day":true,"start_date":"2026-09-24","end_date":"2026-09-24"}"""
                    )
                )))
            else
                flowOf(StreamEvent.Content("acknowledged"))
        }
        val sessionManager = FileSessionManager(createTempDirectory("calendar-create-test"))
        val calendarProvider = FakeCreateOnlyCalendarProvider()

        CalendarCreate(
            provider, calendarProvider, sessionManager, ConfirmationPolicy.DENY_ALL, config,
            TEST_CONTEXT_WINDOW, "Anniversary 2026-09-24"
        ) {}.run()

        calendarProvider.created shouldBe emptyList()
    }
})
