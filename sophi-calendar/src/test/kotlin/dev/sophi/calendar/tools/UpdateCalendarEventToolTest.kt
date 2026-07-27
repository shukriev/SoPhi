package dev.sophi.calendar.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class UpdateCalendarEventToolTest : FunSpec({
    test("riskLevel is DESTRUCTIVE") {
        UpdateCalendarEventTool(FakeCalendarProvider()).riskLevel shouldBe dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
    }

    test("update changes only the provided field") {
        val provider = FakeCalendarProvider()
        val created = provider.create(dev.sophi.calendar.model.CalendarEvent(title = "Old", start = 1L, end = 2L))
        val result = runBlocking {
            UpdateCalendarEventTool(provider).execute("""{"event_id":"${created.id}","title":"New"}""")
        }
        result shouldContain "Updated"
        provider.events[created.id]!!.title shouldBe "New"
        provider.events[created.id]!!.start shouldBe 1L
    }

    test("update with clear_recurrence=true removes an existing recurrence") {
        val provider = FakeCalendarProvider()
        val created = provider.create(
            dev.sophi.calendar.model.CalendarEvent(
                title = "R", start = 1L, end = 2L,
                recurrence = dev.sophi.calendar.model.Recurrence(frequency = dev.sophi.calendar.model.Frequency.DAILY)
            )
        )
        runBlocking { UpdateCalendarEventTool(provider).execute("""{"event_id":"${created.id}","clear_recurrence":true}""") }
        provider.events[created.id]!!.recurrence shouldBe null
    }

    test("update without event_id returns an Error string") {
        val result = runBlocking { UpdateCalendarEventTool(FakeCalendarProvider()).execute("""{"title":"x"}""") }
        result shouldContain "Error"
    }

    test("update with an unknown event_id returns an Error string") {
        val result = runBlocking {
            UpdateCalendarEventTool(FakeCalendarProvider()).execute("""{"event_id":"missing","title":"x"}""")
        }
        result shouldContain "Error"
    }
})
