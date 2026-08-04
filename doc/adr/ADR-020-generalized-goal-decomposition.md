# ADR-020: Generalized goal decomposition / task trees

**Date:** 2026-08-04
**Status:** Accepted — implemented

## Context

ADR-018 made planning a general `sophi-core` capability, but left two gaps. A `Plan` is flat
(one step list plus a `dependsOn` DAG), so a step too large for one `agentLoop.turn()` can only
fail and trigger a tail replan, or fan out invisibly via `delegate_to_subagent` — never
appearing in the plan, `PlanLog`, or replanning. And the only `PlanRunner` construction site in
the repo was `ScheduleEngine`, so nothing outside the scheduler could plan at all.

## Decision

1. **Recursion in the runner, not a new tree type.** `PlanRunner.run()` delegates to a private
   recursive `runPlan(...)`. Tree structure is three defaulted fields (`Plan.parentStepId`,
   `Plan.depth`, `PlanStep.childPlanId`); version lineage keeps its own separate fields
   (`version`, `parentPlanId`). No new module and no `TaskNode` type — rejected as duplicating
   `Plan`/`Planner`/`PlanRunner` for zero behavioural gain.
2. **Two decomposition triggers, one seam.** The planner marks known-large steps
   `decompose: true` and they expand before execution; a step that fails anyway is decomposed
   before the runner falls back to replanning. Both call one `decomposeStep()`. Upfront alone
   would leave a misjudged step with no repair path but tail regeneration — the pre-ADR-018
   behaviour.
3. **One shared `RunBudget` for the whole tree.** Every `agentLoop.turn()` anywhere in the tree
   consumes one unit of `maxStepExecutions`. Per-plan counting would let a two-level tree run
   `maxStepExecutions^2` turns. `maxReplans` stays per-plan. Each step decomposes at most once.
4. **Sub-plans always use `StopCondition.LlmJudged`.** A root `ShellCheck` asks whether the
   whole goal is done, which is not the question being asked of one step.
5. **Failure never aborts the tree and never throws.** An exhausted sub-plan becomes the
   parent's replan reason; only a spent budget or `maxReplans` yields
   `PlanOutcome(Exhausted, ...)` — the state `ScheduleEngine` already maps to
   `RunOutcome.GoalExhausted`.
6. **`PlanLog` is append-only, with no resume.** Resuming would need per-step outputs, session
   pointers, and an idempotency guarantee no tool offers; re-running a half-finished `bash` or
   `invoke_claude_code` step is worse than not resuming.
7. **Two entry points over one factory.** `decompose_goal` (model-invoked, `SubagentTool`'s
   depth-guard pattern) and `/plan <goal>` (user-invoked, `sophi-cli` only) both construct
   through `buildPlanRunner(...)`, so their wiring cannot drift. `ScheduleEngine` keeps its
   inline construction deliberately.
8. **`onPlanComplete` fires once per user-visible run**, at the root only — sub-plans return
   their outcome without invoking the `sophi-learning` feedback seam.
9. **`PlanOutcome` carries `planId` and `finalSteps`.** Neither was in the original design spec,
   but both are needed to make it reachable at all: `planId` fills `PlanStep.childPlanId` and is
   what `/plan` prints; `finalSteps` is the per-step summary both entry points promise. Both are
   defaulted and additive, discovered and added during implementation, and the spec was amended
   to match.

## Consequences

- Oversized steps now decompose instead of dead-ending, and the expansion is visible in the
  plan, in `PlanLog`, and in `PlanOutcome.decompositions`.
- The session tree mirrors the plan tree, since each sub-plan runs in a session parented to its
  step's session — a durable view of the task tree at no extra storage cost.
- `decompose_goal` is session-bound and therefore absent from `buildBuiltinTools`, so the
  scheduler cannot re-enter planning through it (pinned by a guard test). If session-bound tools
  are ever added to the scheduler's registry, a Goal-mode exclusion becomes necessary.
- Live per-step progress streaming is not built: `PlanRunner` has no event seam. A
  `TurnEvent`-style `PlanEvent` callback is the follow-up if the wait proves uncomfortable.

## References

- ADR-018 (plan-and-execute) — the capability this extends.
- ADR-007 / subagent delegation — the depth-guard and session-isolation patterns reused.
- ADR-016 (tiered tool confirmation & grants) — untouched; still the only safety gate.
- Spec: `docs/superpowers/specs/2026-08-04-generalized-goal-decomposition-design.md`.
