package dev.sophi.cli

import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.dryrun.PLAN_TOOL_SERVER_SEPARATOR
import dev.sophi.core.dryrun.PlanStep
import dev.sophi.core.dryrun.StepResult
import dev.sophi.sdk.SophiRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the user decided about a plan; [dropped] are step numbers left out. */
internal sealed interface PlanChoice {
    data class Approve(val dropped: Set<Int>) : PlanChoice
    data class RunAt(val atMs: Long, val dropped: Set<Int>) : PlanChoice
    object Save : PlanChoice
    object Cancel : PlanChoice
}

private const val VALUE_CUT = 40

/** `okta__deactivate_user` → `okta deactivate_user`; a built-in tool is `local <name>`. */
private fun systemAndTool(tool: String): Pair<String, String> =
    if (PLAN_TOOL_SERVER_SEPARATOR in tool) tool.substringBefore(PLAN_TOOL_SERVER_SEPARATOR) to tool.substringAfter(PLAN_TOOL_SERVER_SEPARATOR)
    else "local" to tool

private fun argsLine(args: String, full: Boolean): String {
    val obj = runCatching { Json.parseToJsonElement(args) as? JsonObject }.getOrNull() ?: return args
    return obj.entries.joinToString(" ") { (k, v) ->
        val text = (v as? JsonPrimitive)?.content ?: v.toString()
        "$k=" + if (full || text.length <= VALUE_CUT) text else text.take(VALUE_CUT) + "…"
    }
}

private fun stepLine(s: PlanStep, mask: (String) -> String, full: Boolean, dropped: Set<Int> = emptySet()): String {
    val (system, tool) = systemAndTool(s.tool)
    return mask("${s.n} $system $tool ${argsLine(s.args, full)}".trimEnd()) +
        (if (s.allowedByRule) " (allowed by your rule)" else "") + (if (s.n in dropped) " (dropped)" else "")
}

internal fun renderPlan(plan: ChangePlan, mask: (String) -> String, full: Boolean = false, dropped: Set<Int> = emptySet()): String = buildString {
    val systems = plan.steps.map { systemAndTool(it.tool).first }.distinct().size
    val n = plan.steps.size
    appendLine("${plan.task}: $n change${if (n == 1) "" else "s"} in $systems system${if (systems == 1) "" else "s"}")
    if (plan.reads.isNotEmpty()) {
        appendLine("Looked up (read-only, already ran):")
        plan.reads.forEach { r ->
            val (system, tool) = systemAndTool(r.tool)
            appendLine("  " + mask("$system $tool → ${r.result.lineSequence().first().take(VALUE_CUT)}") + if (r.viaHint) " (read-only per server)" else "")
        }
    }
    appendLine("Changes waiting for you:")
    plan.steps.forEach { appendLine("  " + stepLine(it, mask, full, dropped)) }
}.trimEnd()

private const val KEYS = "[a] approve all  [v] view arguments  [d 1,2] drop steps  [t 18:00] run at a time  [s] save for later  [n] cancel"
private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** `HH:mm` (today, or tomorrow once that's past) or `yyyy-MM-dd HH:mm`, in [zone]; null if neither. */
internal fun parseRunAt(text: String, nowMs: Long, zone: ZoneId): Long? {
    val t = text.trim()
    runCatching { return LocalDateTime.parse(t, DATE_TIME).atZone(zone).toInstant().toEpochMilli() }
    val time = runCatching { LocalTime.parse(t) }.getOrNull() ?: return null
    val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
    val at = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
    return if (at > nowMs) at else today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
}

/** Shows [plan] and reads the user's decision, line by line. No answer (end of input) keeps the plan saved. */
internal suspend fun reviewPlan(
    plan: ChangePlan, input: InputSource, output: (String) -> Unit, mask: (String) -> String,
    nowMs: () -> Long = System::currentTimeMillis, zone: ZoneId = ZoneId.systemDefault(),
): PlanChoice {
    if (plan.steps.size == 1) {
        output("Allow ${stepLine(plan.steps.single(), mask, full = false).substringAfter(' ')}? [y/n/s=save for later]")
        return when (input.readLine()?.trim()?.lowercase()) {
            "y", "yes" -> PlanChoice.Approve(emptySet())
            "n", "no" -> PlanChoice.Cancel
            else -> PlanChoice.Save
        }
    }
    var dropped = emptySet<Int>()
    var full = false
    output(renderPlan(plan, mask, full, dropped)); output(KEYS)
    while (true) {
        val line = input.readLine()?.trim() ?: return PlanChoice.Save
        val (cmd, rest) = line.substringBefore(' ').lowercase() to line.substringAfter(' ', "").trim()
        when (cmd) {
            "a" -> return PlanChoice.Approve(dropped)
            "s" -> return PlanChoice.Save
            "n" -> return PlanChoice.Cancel
            "v" -> { full = !full; output(renderPlan(plan, mask, full, dropped)) }
            "d" -> {
                val wanted = rest.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { n -> plan.steps.any { it.n == n } }.toSet()
                if (wanted.isEmpty()) output("Which steps? For example: d 1,3") else {
                    dropped = dropped + wanted
                    if (dropped.size == plan.steps.size) { output("That drops every step — nothing would run."); dropped = dropped - wanted }
                    output(renderPlan(plan, mask, full, dropped))
                }
            }
            "t" -> parseRunAt(rest, nowMs(), zone)?.let { return PlanChoice.RunAt(it, dropped) }
                ?: output("When? HH:mm (today, or tomorrow if that's past) or yyyy-MM-dd HH:mm")
            else -> output(KEYS)
        }
    }
}

private fun resultLine(plan: ChangePlan, r: StepResult): String {
    val step = plan.steps.firstOrNull { it.n == r.n }
    val (system, tool) = step?.let { systemAndTool(it.tool) } ?: ("" to "")
    return when (r.outcome) {
        "ok" -> "✓ ${r.n} $system $tool"
        "error" -> "✗ ${r.n} $system $tool: ${r.result.removePrefix("Error: ").take(200)}"
        else -> "· ${r.n} $system $tool not run"
    }
}

/** Carries out [choice]: approve and replay (ESC stops it), schedule, keep, or cancel. */
internal suspend fun applyPlanChoice(runtime: SophiRuntime, plan: ChangePlan, choice: PlanChoice, input: InputSource, output: (String) -> Unit) {
    when (choice) {
        is PlanChoice.Approve -> {
            runtime.approvePlan(plan.id, choice.dropped)
            runApprovedPlan(runtime, plan.id, input, output)
        }
        is PlanChoice.RunAt -> {
            runtime.approvePlan(plan.id, choice.dropped)
            runtime.schedulePlan(plan.id, choice.atMs)
            val at = java.time.Instant.ofEpochMilli(choice.atMs).atZone(ZoneId.systemDefault()).format(DATE_TIME)
            output("Scheduled for $at — runs if `sophi schedule daemon` (or the launchd job) is running. sophi plans show ${plan.id}")
        }
        PlanChoice.Save -> output("Saved: sophi plans show ${plan.id}")
        PlanChoice.Cancel -> { runtime.cancelPlan(plan.id); output("Cancelled; nothing ran.") }
    }
}

/** Replays an approved plan, one line per step; ESC stops it between steps' results. */
internal suspend fun runApprovedPlan(runtime: SophiRuntime, id: String, input: InputSource, output: (String) -> Unit) = coroutineScope {
    val plan = runtime.plan(id) ?: return@coroutineScope output("No plan $id")
    val run = async { runtime.runPlan(id) { output(resultLine(plan, it)) } }
    val esc = launch { input.awaitEsc(); run.cancel() }
    try {
        val done = run.await()
        done.results.filter { it.outcome == "not-run" }.forEach { output(resultLine(done, it)) }
    } catch (e: CancellationException) {
        if (!run.isCancelled) throw e
        output("Stopped. What ran: sophi plans show $id")
    } catch (e: Exception) {
        output("Couldn't run the plan: ${e.message}")
    } finally {
        esc.cancel()
    }
}

/** Plan-first for the REPL: a chat turn's plan is reviewed after it; host loops (/goal, /plan) also scope their run. */
class PlanFirstHost internal constructor(private val runtime: SophiRuntime, private val input: InputSource, private val output: (String) -> Unit) {
    suspend fun <T> scope(sessionId: String, task: String, block: suspend () -> T): Pair<T, ChangePlan?> =
        runtime.planFirstScope(sessionId, task, block)

    /** `/plans [list] | show|approve|run|cancel <id>`. */
    suspend fun command(action: String?, id: String?) = plansAction(action, id, runtime, input, output)

    suspend fun review(plan: ChangePlan) =
        applyPlanChoice(runtime, plan, reviewPlan(plan, input, output, runtime::maskSecrets), input, output)
}
