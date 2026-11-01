package dev.sophi.sdk

import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.dryrun.ChangePlanStore
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.StepResult

/** What [RuntimeBuilder.planFirst] set: when turns record changes, whether server read-only hints count, where plans live. */
class PlanFirstSettings(val mode: PlanFirst, val trustReadOnlyHints: Boolean, val store: ChangePlanStore)

/** One line a person reads: `Plan <id>: 1 ok · 2 failed: <why> · 3 not run`. */
fun ChangePlan.replayReport(): String = "Plan $id: " + results.joinToString(" · ") { it.label() }

private fun StepResult.label() = when (outcome) {
    "ok" -> "$n ok"
    "error" -> "$n failed: ${result.removePrefix("Error: ").take(200)}"
    else -> "$n not run"
}
