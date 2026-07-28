package dev.sophi.calendar.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId

class CreateCalendarEventToolTest : FunSpec({
    test("riskLevel is CAUTION") {
        CreateCalendarEventTool(FakeCalendarProvider()).riskLevel("{}") shouldBe dev.sophi.core.tools.RiskLevel.CAUTION
    }

    test("create with required fields persists an event with the correctly parsed local start/end") {
        val provider = FakeCalendarProvider()
        val tool = CreateCalendarEventTool(provider)
        val result = runBlocking {
            tool.execute("""{"title":"Standup","start":"2026-08-01T09:00:00","end":"2026-08-01T09:30:00"}""")
        }
        result shouldContain "Created event"
        val created = provider.events.values.single()
        created.title shouldBe "Standup"
        created.start shouldBe LocalDateTime.parse("2026-08-01T09:00:00").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        created.end shouldBe LocalDateTime.parse("2026-08-01T09:30:00").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    test("create without a title returns an Error string and persists nothing") {
        val provider = FakeCalendarProvider()
        val result = runBlocking {
            CreateCalendarEventTool(provider).execute("""{"start":"2026-08-01T09:00:00","end":"2026-08-01T09:30:00"}""")
        }
        result shouldContain "Error"
        provider.events shouldBe emptyMap()
    }

    test("create with an invalid start datetime returns an Error string and persists nothing") {
        val provider = FakeCalendarProvider()
        val result = runBlocking {
            CreateCalendarEventTool(provider).execute("""{"title":"x","start":"not-a-date","end":"2026-08-01T09:30:00"}""")
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

    test("create with start_date/end_date but no all_day flag returns an Error that names what was actually supplied") {
        val provider = FakeCalendarProvider()
        val result = runBlocking {
            CreateCalendarEventTool(provider).execute(
                """{"title":"Anniversary","start_date":"2026-09-24","end_date":"2026-09-24"}"""
            )
        }
        result shouldContain "Error"
        result shouldContain "start_date"
        result shouldContain "\"all_day\": true"
        provider.events shouldBe emptyMap()
    }

    test("create with an epoch-millis-looking 'start' string returns an Error naming the mistake specifically") {
        val provider = FakeCalendarProvider()
        val result = runBlocking {
            CreateCalendarEventTool(provider).execute(
                """{"title":"x","start":"1758642600000","end":"1759337400000"}"""
            )
        }
        result shouldContain "Error"
        result shouldContain "epoch"
        provider.events shouldBe emptyMap()
    }

    test("create with all_day=true and a start_date carrying a time-of-day suffix returns a clear Error before reaching the provider") {
        val provider = FakeCalendarProvider()
        val result = runBlocking {
            CreateCalendarEventTool(provider).execute(
                """{"title":"Anniversary","all_day":true,"start_date":"2026-09-24T18:30:00","end_date":"2026-09-24"}"""
            )
        }
        result shouldContain "Error"
        result shouldContain "start_date"
        provider.events shouldBe emptyMap()
    }

    test("create with a recurrence object passes it through to the provider") {
        val provider = FakeCalendarProvider()
        runBlocking {
            CreateCalendarEventTool(provider).execute(
                """{"title":"Standup","start":"2026-08-01T09:00:00","end":"2026-08-01T09:30:00","recurrence":{"frequency":"DAILY","interval":1,"count":10}}"""
            )
        }
        provider.events.values.single().recurrence?.count shouldBe 10
    }
})
