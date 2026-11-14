package dev.sophi.calendar.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class GetCalendarEventToolTest : FunSpec({
    test("riskLevel is SAFE") {
        GetCalendarEventTool(FakeCalendarProvider()).riskLevel("{}") shouldBe dev.sophi.core.tools.RiskLevel.SAFE
    }

    test("get returns the event's fields when found") {
        val provider = FakeCalendarProvider()
        val created = provider.create(dev.sophi.calendar.model.CalendarEvent(title = "Standup", start = 1L, end = 2L))
        val result = runBlocking {
            GetCalendarEventTool(provider).execute("""{"event_id":"${created.id}"}""")
        }
        result shouldContain "Standup"
    }

    test("get shows attendee names and the url but never their emails") {
        val provider = FakeCalendarProvider()
        val created = provider.create(
            dev.sophi.calendar.model.CalendarEvent(
                title = "Sync", start = 1L, end = 2L, url = "https://meet.example/a",
                attendees = listOf(dev.sophi.calendar.model.Attendee("Ann", "ann@x.com"))
            )
        )
        val result = runBlocking { GetCalendarEventTool(provider).execute("""{"event_id":"${created.id}"}""") }
        result shouldContain "attendees=Ann"
        result shouldContain "url=https://meet.example/a"
        result.contains("ann@x.com") shouldBe false
    }

    test("get with an unknown event_id returns an Error string") {
        val result = runBlocking {
            GetCalendarEventTool(FakeCalendarProvider()).execute("""{"event_id":"missing"}""")
        }
        result shouldContain "Error"
    }

    test("get without event_id returns an Error string") {
        val result = runBlocking { GetCalendarEventTool(FakeCalendarProvider()).execute("""{}""") }
        result shouldContain "Error"
    }
})
