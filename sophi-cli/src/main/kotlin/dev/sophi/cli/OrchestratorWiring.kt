package dev.sophi.cli

import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.TaskStore

internal const val ORCHESTRATOR_ENABLED_ENV = "SOPHI_ORCHESTRATOR_ENABLED"
internal const val ORCHESTRATOR_TASK_NAME = "self-improvement-researcher"

internal val ORCHESTRATOR_PROMPT = """
    You are Sophi's self-improvement researcher. Once a day, look for evidence-backed opportunities
    to improve Sophi itself — not to write code, only to research and propose.

    Inspect these files directly (read_file, grep):
    - ~/.sophi/schedule/runs.jsonl — past scheduled-task outcomes
    - ~/.sophi/learning/lessons.jsonl — active lessons and how often each is recalled
    - ~/.sophi/learning/lesson-usage.jsonl — which lessons were recalled in which session
    - ~/.sophi/learning/tool-events.jsonl — tool call success/failure history
    - ~/.sophi/learning/session-outcomes.jsonl — how sessions concluded
    - ~/.sophi/skills/.attribution.jsonl — per-skill-version invocation counts and adjacent tool
      failures, refreshed by `sophi skill review`; may be missing or empty if no one has run it yet
    - ~/.sophi/skills/.unattributed.jsonl — invocations of a skill with no matching recorded
      version, also refreshed by `sophi skill review`
    - ~/.sophi/memory/consolidations.jsonl — every memory consolidation run: merged/strengthened/
      compressed/pruned/purged counts, which memory ids were soft-deleted or physically purged,
      and whether auto-purge was enabled for that run

    Look for patterns: a lesson recalled often but never correlated with a good outcome, a tool that
    fails disproportionately, a recurring theme across session outcomes. When you have one concrete,
    evidence-backed suggestion, call propose_improvement with your finding. Do not propose vague or
    speculative ideas — every proposal must cite specific evidence from the files above.

    You have no ability to modify Sophi, run shell commands, or write files — only to read and
    research. This is deliberate: a human reviews every proposal before anything changes.
""".trimIndent()

internal fun bootstrapOrchestrator(taskStore: TaskStore, env: (String) -> String? = System::getenv) {
    val enabled = env(ORCHESTRATOR_ENABLED_ENV)?.lowercase() == "true"
    val existing = taskStore.list().find { it.name == ORCHESTRATOR_TASK_NAME }
    if (!enabled) {
        if (existing?.enabled == true) taskStore.setEnabled(existing.id, false)
        return
    }
    when {
        existing == null -> taskStore.add(
            ScheduledTask(
                name = ORCHESTRATOR_TASK_NAME,
                trigger = Trigger.Interval(everySeconds = 86_400),
                mode = TaskMode.Goal(StopCondition.LlmJudged, maxIterations = 8),
                prompt = ORCHESTRATOR_PROMPT,
                toolGrants = emptySet(),
                maxWallClockMsPerWindow = 3_600_000L, wallClockWindowMs = 86_400_000L
            )
        )
        else -> {
            if (!existing.enabled) taskStore.setEnabled(existing.id, true)
            if (existing.prompt != ORCHESTRATOR_PROMPT) taskStore.update(existing.id) { it.copy(prompt = ORCHESTRATOR_PROMPT) }
        }
    }
}
