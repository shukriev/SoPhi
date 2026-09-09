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

    /** Returns the [CheckIn]s whose next cron occurrence has arrived by [nowMs] and hasn't
     *  already been returned by an earlier call. */
    fun checkAndFire(nowMs: Long): List<CheckIn> {
        val fired = mutableListOf<CheckIn>()
        for (checkIn in checkIns) {
            val after = lastFiredAtMs[checkIn.name] ?: (nowMs - 1)
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
