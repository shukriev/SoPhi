package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class GetCurrentDateTimeToolTest : FunSpec({
    test("riskLevel is SAFE") {
        GetCurrentDateTimeTool().riskLevel("{}") shouldBe RiskLevel.SAFE
    }

    test("returns epoch millis, ISO datetime, timezone, and day of week for a fixed clock") {
        val fixedInstant = Instant.parse("2026-07-27T12:00:00Z")
        val zone = ZoneId.of("UTC")
        val clock = Clock.fixed(fixedInstant, zone)
        val expectedDayOfWeek = ZonedDateTime.ofInstant(fixedInstant, zone).dayOfWeek

        val result = runBlocking { GetCurrentDateTimeTool(clock).execute("{}") }

        result shouldContain "epoch_ms=${fixedInstant.toEpochMilli()}"
        result shouldContain "timezone=UTC"
        result shouldContain "day_of_week=$expectedDayOfWeek"
    }

    test("uses the system default zone by default") {
        val result = runBlocking { GetCurrentDateTimeTool().execute("{}") }
        result shouldContain "timezone=${ZoneId.systemDefault().id}"
    }
})
