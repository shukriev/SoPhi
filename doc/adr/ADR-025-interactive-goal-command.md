# ADR-025: Interactive `/goal` command

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
3. **Extend ADR-020's `PlanProgressEvent`; do not add a second event stream.** This work was
   developed in parallel with ADR-020, and each side grew its own progress type — a `PlanEvent`
   here, `PlanProgressEvent` there. They overlap almost entirely (`StepFinished`, `Replanned`),
   so they are now one type. `PlanProgressEvent` gains `PlanReady`, `StepAttempt` (which model,
   which child session, first attempt or the escalation re-run) and `Escalating`;
   `StepStarted`/`StepFinished` carry the plan version; `Replanned` carries the replacement
   `Plan` itself rather than just its id, which is what lets a live UI re-render the step list.
   What is *not* merged is `PlanRunner.onEvent`, the raw `TurnEvent` seam: the hub/companion
   bridge wants turn events unwrapped and plan-agnostic, and folding them into the narrative
   stream would make a per-token event masquerade as a plan milestone. A renderer that needs
   both — `GoalRenderer` does — subscribes to both, which is safe because `PlanRunner` always
   fires the boundary event before the turn events of the step it opens.
4. **`PlanRunner.run()` gains `initialPlan`** so the CLI can generate a plan, show it, and only
   pass it to `run()` after approval — no new `PlanFinalStatus.Aborted` value needed, and no
   second `planner.plan()` call that could return something other than what the user approved.
5. **ESC aborts the whole goal, not the current step.** A step-level abort would read to
   `PlanRunner` as a step failure, which would then replan the very thing just cancelled.
6. **`allowParallelSteps` stays `false`** — reaffirming ADR-018 §5: an interactive session
   always has a human attending, and overlapping confirmation prompts from concurrent steps
   would be confusing.
7. **CLI-only v1.** `sophi-web` is out of scope. Two small `sophi-sdk` seams were needed rather
   than worked around: `SophiRuntime.contextFor` and `SophiRuntime.settleExternalTurn`. /goal
   drives `PlanRunner` directly instead of `SophiRuntime.streamTurn`, so without them its
   episode would silently skip AFTER_TURN hooks (memory encoding, learning) and plan without
   memory context. `PlanProgressEvent` stays a UI-agnostic seam a future web/SSE consumer can
   subscribe to without redesign.

## Consequences

- `TurnController`'s render state moved into `StreamingTurnPresenter` so `GoalController` can
  drive identical spinner/token/tool-call rendering per plan step. Existing `TurnControllerTest`
  and `StreamingIntegrationTest` suites pass unmodified — the proof the extraction didn't
  change turn-rendering behavior.
- `sophi-schedule`'s existing `PlanRunner` usage is unaffected: `initialPlan` defaults to `null`
  and the new `PlanProgressEvent` variants only reach a caller that supplied an `onProgress`.
- `/plan`'s `PlanProgressRenderer` picked up the new variants for free: it now reports an
  escalation and an escalation re-run, which it could not before.
- `PlanLog` is written in exactly one place — `PlanRunner` itself, which already logged plan
  versions for the /plan and decompose_goal paths. `GoalController` hands its approved plan in
  as `initialPlan` and lets the runner log it, rather than logging it a second time.
- `/plan` (ADR-020) and `/goal` now overlap: both plan-and-execute a goal, and only `/goal`
  previews and gates. Deliberately left as two commands for now — `/plan` is the
  fire-and-forget form and `/goal` the attended one — but they are a merge candidate.

## References

- ADR-020 (generalized goal decomposition) — `PlanProgressEvent`, the recursive sub-plan
  execution, and `buildPlanRunner`; this ADR extends its event type rather than paralleling it.
- ADR-018 (plan-and-execute) — `PlanRunner`, `Planner`, `StepCritic`, the isolation model this
  design builds directly on top of.
- ADR-016/017 (tiered confirmation, auto mode) — `ConfirmationPolicy`/`TerminalConfirmationPolicy`,
  unchanged; plan steps go through the exact same gate as ordinary turns.
