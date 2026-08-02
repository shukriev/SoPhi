# ADR-019: Interactive `/goal` command

**Date:** 2026-08-02
**Status:** Accepted — implemented

## Context

`PlanRunner` (ADR-018) was wired only into `sophi-schedule`'s unattended background tasks.
A human in a live chat session had no way to ask for explicit, multi-step planning.

## Decision

1. **Session visibility: hybrid, not full isolation or full flattening.** Steps still execute
   in `PlanRunner`'s existing isolated child sessions. The anchor session gets one
   `replay=false` entry per finished step (visible to `/branch`, invisible to future prompts)
   plus one replayed summary entry, so a follow-up question after a goal has something
   coherent to react to without importing every step's full tool-call transcript into the
   session's prompt budget.
2. **Plan preview gate, using `input.readLine()` not `input.awaitYesNo()`.** `awaitYesNo()`
   only resolves while `awaitControlKeys`'s raw-mode reader loop is running (started by
   `TurnController` during a turn); no such loop exists while a slash command executes, so
   calling it from `GoalController` would hang the REPL forever. `GoalController` runs its own
   `awaitControlKeys` race for the ESC path and for step-level `ConfirmationPolicy` prompts,
   which is what makes those still work correctly inside plan steps.
3. **`PlanRunner` gains three additive, defaulted parameters**: `onPlanEvent` (a `PlanEvent`
   progress stream — `PlanRunner` had no way to signal that a step started), `initialPlan` (so
   the CLI can generate a plan, show it, and only pass it to `run()` after approval — no new
   `PlanFinalStatus.Aborted` value needed), and `PlanRunnerConfig.systemPrompt` (plan steps
   previously ran with no system prompt at all — a latent gap, not new to this feature, that
   would otherwise make `/goal` behave like a different agent than the surrounding chat).
4. **ESC aborts the whole goal, not the current step.** A step-level abort would read to
   `PlanRunner` as a step failure, which would then replan the very thing just cancelled.
5. **`allowParallelSteps` stays `false`** — reaffirming ADR-018 §5: an interactive session
   always has a human attending, and overlapping confirmation prompts from concurrent steps
   would be confusing.
6. **CLI-only v1.** `sophi-web`/`sophi-sdk` are out of scope; `PlanEvent` is a UI-agnostic seam
   a future web/SSE consumer can subscribe to without redesign.

## Consequences

- `TurnController`'s render state moved into `StreamingTurnPresenter` so `GoalController` can
  drive identical spinner/token/tool-call rendering per plan step. Existing `TurnControllerTest`
  and `StreamingIntegrationTest` suites pass unmodified — the proof the extraction didn't
  change turn-rendering behavior.
- `sophi-schedule`'s existing `PlanRunner` usage is unaffected: the three new parameters all
  default to today's behavior.
- No plan-inspection UI is built; `PlanLog` gets its first production caller in
  `GoalController`, making replan history persisted (not just tested) for a future UI to read.

## References

- ADR-018 (plan-and-execute) — `PlanRunner`, `Planner`, `StepCritic`, the isolation model this
  design builds directly on top of.
- ADR-016/017 (tiered confirmation, auto mode) — `ConfirmationPolicy`/`TerminalConfirmationPolicy`,
  unchanged; plan steps go through the exact same gate as ordinary turns.
