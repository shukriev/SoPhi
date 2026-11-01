package dev.sophi.cli

import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.dryrun.PlanRead
import dev.sophi.core.dryrun.PlanStep
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId

private val zone = ZoneId.of("Europe/Berlin")
private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

private fun plan(vararg steps: PlanStep, reads: List<PlanRead> = emptyList()) =
    ChangePlan("plan_r", 1L, "offboard jane@acme.com", "s1", reads = reads, steps = steps.toList())

private val okta = PlanStep(1, "okta__deactivate_user", """{"id":"00u9f","token":"sekret-value-123"}""", RiskLevel.DESTRUCTIVE, false)
private val github = PlanStep(2, "github__remove_org_member", """{"org":"acme","user":"jdoe"}""", RiskLevel.DESTRUCTIVE, true)

class PlanReviewTest : FunSpec({
    val mask: (String) -> String = { it.replace("sekret-value-123", "[REDACTED:secret]") }
    val now = { at(2026, 11, 2, 10, 0) }

    test("renderPlan lists reads, steps, rule marks and masks secrets") {
        val text = renderPlan(plan(okta, github, reads = listOf(PlanRead("okta__find_user", """{"q":"jane"}""", "00u9f", viaHint = true))), mask)
        text shouldContain "offboard jane@acme.com: 2 changes in 2 systems"
        text shouldContain "Looked up (read-only, already ran):"
        text shouldContain "okta find_user → 00u9f (read-only per server)"
        text shouldContain "Changes waiting for you:"
        text shouldContain "1 okta deactivate_user id=00u9f"
        text shouldContain "2 github remove_org_member org=acme user=jdoe (allowed by your rule)"
        text shouldNotContain "sekret-value-123"
    }

    test("long values are cut in the list and shown whole with v") {
        val long = okta.copy(args = """{"note":"${"x".repeat(80)}"}""")
        renderPlan(plan(long), mask) shouldNotContain "x".repeat(80)
        renderPlan(plan(long), mask, full = true) shouldContain "x".repeat(80)
    }

    test("a one-step plan asks a single yes/no") {
        val out = mutableListOf<String>()
        runBlocking { reviewPlan(plan(okta), ScriptedInputSource(listOf("y")), out::add, mask, now, zone) } shouldBe PlanChoice.Approve(emptySet())
        out.joinToString("\n") shouldContain "Allow okta deactivate_user id=00u9f"
        runBlocking { reviewPlan(plan(okta), ScriptedInputSource(listOf("n")), {}, mask, now, zone) } shouldBe PlanChoice.Cancel
        runBlocking { reviewPlan(plan(okta), ScriptedInputSource(listOf("s")), {}, mask, now, zone) } shouldBe PlanChoice.Save
    }

    test("d then a approves without the dropped step") {
        runBlocking { reviewPlan(plan(okta, github), ScriptedInputSource(listOf("d 1", "a")), {}, mask, now, zone) } shouldBe
            PlanChoice.Approve(setOf(1))
    }

    test("v shows full arguments, masked") {
        val out = mutableListOf<String>()
        runBlocking { reviewPlan(plan(okta, github), ScriptedInputSource(listOf("v", "a")), out::add, mask, now, zone) }
        out.joinToString("\n") shouldContain "token=[REDACTED:secret]"
    }

    test("t parses a time and schedules") {
        runBlocking { reviewPlan(plan(okta, github), ScriptedInputSource(listOf("t 18:00")), {}, mask, now, zone) } shouldBe
            PlanChoice.RunAt(at(2026, 11, 2, 18, 0), emptySet())
        // a time already past today means tomorrow
        runBlocking { reviewPlan(plan(okta, github), ScriptedInputSource(listOf("t 09:00")), {}, mask, now, zone) } shouldBe
            PlanChoice.RunAt(at(2026, 11, 3, 9, 0), emptySet())
        runBlocking { reviewPlan(plan(okta, github), ScriptedInputSource(listOf("t 2026-11-06 18:30")), {}, mask, now, zone) } shouldBe
            PlanChoice.RunAt(at(2026, 11, 6, 18, 30), emptySet())
    }

    test("a time it can't read asks again") {
        val out = mutableListOf<String>()
        runBlocking { reviewPlan(plan(okta, github), ScriptedInputSource(listOf("t friday", "s")), out::add, mask, now, zone) } shouldBe PlanChoice.Save
        out.joinToString("\n") shouldContain "HH:mm"
    }

    test("n cancels the plan; no answer keeps it saved") {
        runBlocking { reviewPlan(plan(okta, github), ScriptedInputSource(listOf("n")), {}, mask, now, zone) } shouldBe PlanChoice.Cancel
        runBlocking { reviewPlan(plan(okta, github), ScriptedInputSource(emptyList()), {}, mask, now, zone) } shouldBe PlanChoice.Save
    }

    test("settings menu lists plan-first and trust-read-only-hints") {
        CLI_SETTINGS.single { it.key == "plan-first" }.kind shouldBe SettingKind.Choice(listOf("outside", "every", "off"))
        CLI_SETTINGS.single { it.key == "trust-read-only-hints" }.kind shouldBe SettingKind.Bool
    }
})
