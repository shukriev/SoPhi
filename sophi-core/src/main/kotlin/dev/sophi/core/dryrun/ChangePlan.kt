package dev.sophi.core.dryrun

import dev.sophi.core.audit.LOCAL_ACTOR
import dev.sophi.core.tools.RiskLevel
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** When a turn records changes instead of making them: never, changes in another system (MCP), or every change. */
enum class PlanFirst { OFF, OUTSIDE, EVERY }

@Serializable
enum class PlanStatus { PENDING, APPROVED, RUNNING, DONE, FAILED, CANCELLED }

/** A lookup that ran for real during the dry run; [viaHint] = it ran only because the server called it read-only. */
@Serializable
data class PlanRead(val tool: String, val args: String, val result: String, val viaHint: Boolean)

@Serializable
data class PlanStep(val n: Int, val tool: String, val args: String, val risk: RiskLevel, val allowedByRule: Boolean)

/** [outcome]: `ok`, `error` or `not-run`. */
@Serializable
data class StepResult(val n: Int, val outcome: String, val result: String)

/**
 * The changes a dry run recorded, waiting for (or past) the user's approval. Replay runs [approvedSteps] exactly,
 * and only while [approvedHash] still matches them.
 */
@Serializable
data class ChangePlan(
    val id: String,
    val createdAtMs: Long,
    val task: String,
    val sessionId: String,
    val actor: String = LOCAL_ACTOR,
    val tenant: String? = null,
    val reads: List<PlanRead>,
    val steps: List<PlanStep>,
    val status: PlanStatus = PlanStatus.PENDING,
    val approvedAtMs: Long? = null,
    val approvedHash: String? = null,
    val dropped: Set<Int> = emptySet(),
    val results: List<StepResult> = emptyList(),
)

fun ChangePlan.approvedSteps(): List<PlanStep> = steps.filter { it.n !in dropped }.sortedBy { it.n }

fun ChangePlan.stepsHash(): String = MessageDigest.getInstance("SHA-256")
    .digest(approvedSteps().joinToString("") { it.tool + "\u0000" + it.args + "\n" }.toByteArray())
    .joinToString("") { "%02x".format(it) }

/** What the model gets back for a recorded call. */
fun planStepStub(n: Int): String =
    "Not run yet: recorded as plan step $n. It runs only if the user approves the plan after this turn. " +
        "Don't say it happened; finish planning and summarise what you intend to change."

/** MCP tools are named `server__tool`; the server part is what the plan screen shows as the system. */
const val PLAN_TOOL_SERVER_SEPARATOR = "__"
