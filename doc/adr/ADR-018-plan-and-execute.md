# ADR-018: Plan-and-Execute upgrade to goal-driven runs

**Date:** 2026-08-01
**Status:** Accepted (design) — not yet implemented

## Context

`GoalRunner` (`sophi-schedule`) is the only place Sophi runs a goal across multiple turns,
and it does so by repeating `agentLoop.turn(current, "Continue working towards the goal. It
has not been met yet.", config)` until an `LlmJudged`/`ShellCheck` `StopCondition` fires.
There is no explicit plan — no ordered subgoal list, nothing inspectable, nothing that can
be partially replaced when one part fails or partially reused when most of it succeeded.

Separately, `sophi-core` already has a "cheap judge call with a fail-safe default" pattern
(`RiskClassifier`/`LlmRiskClassifier`, ADR-017) that generalizes cleanly to two other needs:
scoring a step's confidence, and picking a stronger model only when needed.

## Decision

1. **A general `sophi-core` capability, not a `sophi-schedule`-only upgrade.** `PlanRunner`,
   `Plan`/`PlanStep`, `Planner`, and `StepCritic` live in `dev.sophi.core.agent.plan`,
   sitting above `AgentLoop` exactly where `GoalRunner` sits today. `AgentLoop` itself is
   unchanged — a single-turn ReAct engine with no knowledge of plans. This is a deliberate
   placement choice over keeping the upgrade inside `sophi-schedule`: `sophi-schedule` is the
   only *current* consumer of multi-turn goal execution, but nothing about planning is
   scheduler-specific, and a second bespoke planner would be needed later if interactive
   turn-based use ever wants explicit planning too.
2. **`sophi-core` depending on `sophi-ai` is established precedent, not a new exception.**
   `RiskClassifier`/`LlmRiskClassifier` (ADR-017) already put `LLMProvider` calls inside
   `sophi-core`. The planner and step-critic LLM calls follow the same precedent. The rule
   that still holds unmodified is `sophi-ai` never imports `sophi-core`.
3. **Memory/learning integration via injected callbacks, not direct dependencies.**
   `sophi-extensions` (the `ContextContributor`/`PluginRegistry` SPI) and `sophi-learning`
   (`SessionEvaluator`/`LessonStore`) both depend on `sophi-core`, not the reverse —
   `sophi-core` cannot import either without inverting that direction. So:
   - `Planner` takes an optional `contextProvider: suspend (goalPrompt: String) -> List<String>`
     lambda; the wiring layer supplies one that calls `pluginRegistry.collectContext(...)`.
   - `PlanRunner` takes an optional `onPlanComplete: suspend (PlanOutcome) -> Unit` callback;
     the wiring layer translates the plain `PlanOutcome` data class into whatever
     `sophi-learning` needs (a new optional `SessionOutcome.planningNote` field and a new
     `"planning"` `Lesson.kind` value).
   This mirrors how `AgentLoop` already takes `ConfirmationPolicy` as an injected interface
   rather than knowing about `TerminalConfirmationPolicy`/`AutoModeConfirmationPolicy`
   concretely — `sophi-core` defines the seam, higher modules supply the behavior.
4. **Diff-based replanning, not full regeneration.** A replan produces a new `Plan` with
   `version = parent.version + 1`, carrying every already-`Done` step over unchanged and
   only regenerating the failed/low-confidence tail. This makes plan history auditable
   (v1 → v2 → v3, each a complete snapshot) and avoids redoing completed work — the
   alternative (throw away the whole plan and regenerate from scratch) was rejected because
   it loses both properties for a marginal implementation simplification.
5. **Explicit `allowParallelSteps` flag, not `ConfirmationPolicy` introspection.** Independent
   plan steps can run concurrently (same `async`/`awaitAll` shape `AgentLoop` already uses
   for tool-call rounds and `ScheduleEngine.tickOnce()` uses for concurrent task execution),
   but concurrent steps each independently trigger `ConfirmationPolicy.confirm()`, and
   overlapping human-facing prompts would be confusing. Rather than teaching `PlanRunner` to
   detect whether a given policy is interactive — which would mean adding a property to the
   `ConfirmationPolicy` interface and touching every implementor — `PlanRunnerConfig` takes an
   explicit `allowParallelSteps: Boolean = false`, set by whoever constructs the `PlanRunner`
   and therefore already knows their own confirmation-policy context. `sophi-schedule`'s
   `ScheduleEngine` (always unattended, `DENY_DESTRUCTIVE` + per-task allowlist per ADR-014)
   sets it `true`; any future interactive caller keeps the safe default.
6. **"Plan always," no complexity-threshold branch.** Every goal-driven run goes through the
   planner, even for a one- or two-step goal — the planner naturally produces a short plan
   for simple goals. This avoids a second flat-loop code path and a threshold to tune, and it
   makes a separate upfront "is this goal complex enough to plan" classifier unnecessary.
7. **`GoalRunner` is retired, not kept alongside `PlanRunner`.** `sophi-schedule`'s
   `engine/GoalRunner.kt` and `model/StopCondition.kt` are deleted; `StopCondition` moves to
   `dev.sophi.core.agent.plan`, and `ScheduleEngine.runTask()`'s `TaskMode.Goal` branch
   constructs a `PlanRunner` instead. No other `sophi-schedule` surface changes — `sophi goal
   run <id>` and the rest of ADR-014's CLI/tool surface call into `ScheduleEngine` exactly as
   before.

## Consequences

- `sophi-schedule` goal-driven tasks get replanning, confidence-scored escalation, and
  (where safe) parallel step execution for free, without a second implementation.
- Interactive (`sophi-cli`/`sophi-web`/`sophi-sdk`) goal-mode is now architecturally possible
  without a second planner, but is not built in this iteration — there's no existing
  interactive consumer to migrate.
- `StepCritic` fails **open** (assumes success) on judge failure, the opposite of
  `RiskClassifier`'s fail-**closed**-to-`HIGH_RISK` behavior — intentional, since this is an
  efficiency/quality gate, not a safety gate. A missed low-confidence signal only costs a
  skipped optional escalation; a missed `HIGH_RISK` verdict could let a destructive call
  through unconfirmed. `ConfirmationPolicy`/`RiskLevel` remain the only safety gate and are
  untouched by this design.
- A plan-inspection UI, tree-of-thought/multi-path plan search, and cross-plan coordination
  between concurrently running goals are explicitly out of scope, tracked in `TODO_TASK.md`.

## References

- ADR-014 (scheduled & goal-based tasks) — the `GoalRunner`/`StopCondition` this design
  retires and replaces.
- ADR-017 (auto mode hybrid risk classifier) — the `sophi-core`→`sophi-ai` precedent and the
  judge-call pattern `StepCritic` follows.
- ADR-013 (memory as plugin/context contributor) — the `ContextContributor`/`PluginRegistry`
  SPI reused (via injected callback) for memory-informed planning.
