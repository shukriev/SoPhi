package dev.sophi.companion

import dev.sophi.schedule.model.CronSchedules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tracks, per [CheckIn], the last cron occurrence handled — so repeated [checkAndFire] calls fire
 * each scheduled occurrence exactly once. In-memory only: a companion restart forgets what already
 * fired today, so a missed/asleep period is simply not caught up (accepted, see the design spec).
 */
class CheckInScheduler(private val checkIns: List<CheckIn>) {
    private val lastFiredAtMs = mutableMapOf<String, Long>()

    /** The [nowMs] of the previous [checkAndFire] call, or null before the first one. Anchors
     *  the "haven't fired yet" fallback below to a stable point in time instead of recomputing it
     *  from the current tick — see that fallback's doc for why that recomputation was a bug. */
    private var lastCheckedMs: Long? = null

    /** Returns the [CheckIn]s whose next cron occurrence has arrived by [nowMs] and hasn't
     *  already been returned by an earlier call. */
    fun checkAndFire(nowMs: Long): List<CheckIn> {
        // Production polling calls this on a fixed wall-clock interval unrelated to any cron
        // schedule's phase, so nowMs almost never lands exactly on a fire boundary. Before a
        // check-in has fired even once, its fallback reference point must be the last time this
        // was CALLED (lastCheckedMs), not (nowMs - 1) recomputed from the CURRENT call: the old
        // (nowMs - 1) fallback made "next occurrence" always resolve to something already in the
        // future relative to that same nowMs, so a boundary that passed between two ticks could
        // never be observed — the check-in would never fire, ever.
        val since = lastCheckedMs ?: (nowMs - 1)
        lastCheckedMs = nowMs
        val fired = mutableListOf<CheckIn>()
        for (checkIn in checkIns) {
            val after = lastFiredAtMs[checkIn.name] ?: since
            val next = CronSchedules.nextFireTimeAfter(checkIn.cronExpression, after)
            if (next != null && next <= nowMs) {
                lastFiredAtMs[checkIn.name] = next
                fired.add(checkIn)
            }
        }
        return fired
    }
}

private val PROMPT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The turn prompt handed to the vault-scoped check-in agent for one captured reply. The
 * obsidian-worklog skill (`~/.sophi/skills/obsidian-worklog.md`) reads [checkInName] to pick a
 * note section and the Jira-base-URL line to decide whether to link bare ticket IDs.
 */
fun buildCheckInPrompt(
    checkInName: String,
    capturedText: String,
    jiraBaseUrl: String?,
    capturedAt: Instant = Instant.now()
): String {
    val time = PROMPT_TIME_FORMAT.format(capturedAt.atZone(ZoneId.systemDefault()))
    val jiraLine = jiraBaseUrl?.let { "Jira base URL: $it" }
        ?: "No Jira base URL configured — leave any ticket IDs as plain text."
    return """
        Check-in '$checkInName': $capturedText
        Captured at: $time
        $jiraLine
    """.trimIndent()
}
