package dev.sophi.calendar.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class DeleteCalendarEventToolTest : FunSpec({
    test("riskLevel is DESTRUCTIVE") {
        DeleteCalendarEventTool(FakeCalendarProvider()).riskLevel shouldBe dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
    }

    test("delete removes the event") {
        val provider = FakeCalendarProvider()
        val created = provider.create(dev.sophi.calendar.model.CalendarEvent(title = "Gone", start = 1L, end = 2L))
        val result = runBlocking { DeleteCalendarEventTool(provider).execute("""{"event_id":"${created.id}"}""") }
        result shouldContain "Deleted"
        provider.events shouldBe emptyMap()
    }

    test("delete with an unknown event_id returns an Error string") {
        val result = runBlocking { DeleteCalendarEventTool(FakeCalendarProvider()).execute("""{"event_id":"missing"}""") }
        result shouldContain "Error"
    }

    test("delete without event_id returns an Error string") {
        val result = runBlocking { DeleteCalendarEventTool(FakeCalendarProvider()).execute("""{}""") }
        result shouldContain "Error"
    }
})
