package dev.sophi.calendar.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class CreateCalendarEventToolTest : FunSpec({
    test("riskLevel is SAFE") {
        CreateCalendarEventTool(FakeCalendarProvider()).riskLevel shouldBe dev.sophi.core.tools.RiskLevel.SAFE
    }

    test("create with required fields persists an event and returns its id") {
        val provider = FakeCalendarProvider()
        val tool = CreateCalendarEventTool(provider)
        val result = runBlocking {
            tool.execute("""{"title":"Standup","start":1785700000000,"end":1785703600000}""")
        }
        result shouldContain "Created event"
        provider.events.values.single().title shouldBe "Standup"
    }

    test("create without a title returns an Error string and persists nothing") {
        val provider = FakeCalendarProvider()
        val result = runBlocking {
            CreateCalendarEventTool(provider).execute("""{"start":1,"end":2}""")
        }
        result shouldContain "Error"
        provider.events shouldBe emptyMap()
    }

    test("create with allDay=true requires startDate/endDate instead of start/end") {
        val provider = FakeCalendarProvider()
        val result = runBlocking {
            CreateCalendarEventTool(provider).execute("""{"title":"Vacation","all_day":true,"start_date":"2026-08-01","end_date":"2026-08-05"}""")
        }
        result shouldContain "Created event"
        provider.events.values.single().allDay shouldBe true
    }

    test("create with a recurrence object passes it through to the provider") {
        val provider = FakeCalendarProvider()
        runBlocking {
            CreateCalendarEventTool(provider).execute(
                """{"title":"Standup","start":1,"end":2,"recurrence":{"frequency":"DAILY","interval":1,"count":10}}"""
            )
        }
        provider.events.values.single().recurrence?.count shouldBe 10
    }
})
