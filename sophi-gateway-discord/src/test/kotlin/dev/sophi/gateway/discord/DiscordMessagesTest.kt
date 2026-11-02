package dev.sophi.gateway.discord

import dev.sophi.gateway.GatewayOut
import dev.sophi.gateway.GatewayResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.time.Instant
import java.time.ZoneOffset

class DiscordMessagesTest : FunSpec({
    val utc = ZoneOffset.UTC
    val now = Instant.parse("2026-11-01T14:02:00Z").toEpochMilli()

    test("short text is one part") {
        splitForDiscord("hello") shouldBe listOf("hello")
    }

    test("long text splits at paragraphs with (1/2) (2/2), every part ≤ 2000") {
        val para = "word ".repeat(250).trim()   // 1,249 characters
        val parts = splitForDiscord("$para\n\n$para")
        parts shouldHaveSize 2
        parts[0] shouldBe "$para\n(1/2)"
        parts[1] shouldBe "$para\n(2/2)"
        parts.forEach { it.length shouldBeLessThanOrEqual 2000 }
    }

    test("a fence across the cut is closed and reopened") {
        val code = (1..120).joinToString("\n") { "val line$it = $it // some padding text" }
        val parts = splitForDiscord("Here:\n```kotlin\n$code\n```\nDone.")
        parts.size shouldBe 3
        parts.forEach { it.length shouldBeLessThanOrEqual 2000 }
        parts[0].substringBeforeLast("\n(") shouldEndWith "```"
        parts[1] shouldStartWith "```kotlin\n"
        parts.forEach { p -> (Regex("^```", RegexOption.MULTILINE).findAll(p).count() % 2) shouldBe 0 }
    }

    test("permission with approve has Allow and Deny and no session button") {
        val m = render(GatewayOut.Permission("k1", "s1", "Weekly planning", "Run `ls`", canAnswer = true, tainted = false, now), now, utc).single()
        m.content shouldBe "**SoPhi asks** · Weekly planning\nRun `ls`"
        val buttons = m.components.single().components
        buttons.map { it.label } shouldBe listOf("Allow", "Deny")
        buttons.map { it.style } shouldBe listOf(3, 4)
        buttons.map { it.customId } shouldBe listOf("p:k1:allow", "p:k1:deny")
    }

    test("permission without approve has no components") {
        val m = render(GatewayOut.Permission("k1", "s1", "Weekly planning", "Run `ls`", canAnswer = false, tainted = false, now), now, utc).single()
        m.content shouldBe "**SoPhi needs you on your Mac** · Weekly planning: Run `ls`."
        m.components shouldBe emptyList()
    }

    test("tainted permission carries the outside-text line") {
        render(GatewayOut.Permission("k1", "s1", "Phone", "Click Send", canAnswer = true, tainted = true, now), now, utc)
            .single().content shouldEndWith "\nThis chat has outside text in it, so each step asks."
    }

    test("question with 7 choices makes two rows") {
        val m = render(GatewayOut.Question("q1", "Which?", (1..7).map { "Option $it" }, allowFreeText = true, now), now, utc).single()
        m.content shouldBe "Which?\nOr reply in your own words."
        m.components.map { it.components.size } shouldBe listOf(5, 2)
        m.components[0].components[0].label shouldBe "A  Option 1"
        m.components[1].components[1].customId shouldBe "q:q1:6"
        m.components.flatMap { it.components }.forEach { it.style shouldBe 2 }
    }

    test("late notice says when it was due") {
        val due = Instant.parse("2026-11-01T07:30:00Z").toEpochMilli()
        render(GatewayOut.Notice("Reminder", "Call the bank", due), now, utc).single().content shouldBe
            "**Reminder** · Call the bank (due 07:30, sent when your Mac woke)"
        render(GatewayOut.Notice("Reminder", "Call the bank", now - 60_000), now, utc).single().content shouldNotContain "due"
    }

    test("click texts for every GatewayResult") {
        clickText(GatewayResult.Answered(true), null, now, utc) shouldBe "Allowed from your phone · 14:02"
        clickText(GatewayResult.Answered(false), null, now, utc) shouldBe "Denied from your phone · 14:02"
        clickText(GatewayResult.Answered(null), "B  Tomorrow", now, utc) shouldBe "B  Tomorrow"
        clickText(GatewayResult.AlreadyAnswered, null, now, utc) shouldBe "Answered on the Mac"
        clickText(GatewayResult.NotAllowed, null, now, utc) shouldBe "Approve this on your Mac."
        ENDED_ON_MAC shouldContain "ended on the Mac"
    }
})
