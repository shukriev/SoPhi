# ADR-014: Scheduled & goal-based tasks

**Date:** 2026-07-22
**Status:** Implemented

## Context

Sophi could previously only act inside a live, human-driven chat turn. We wanted Sophi to
run recurring monitoring/summarization work and goal-driven retries without a human
watching — the equivalent of Claude Code's `/loop`, `/goal`, and proactive
(schedule + goal + no supervision) loop types. Turn-based loops already exist (the
ordinary chat REPL) and needed no change.

## Decision

1. **Trigger × Mode, not four separate mechanisms.** A `ScheduledTask` combines an
   independent `Trigger` (when it fires: `Interval`/`Once`/`Manual`) and `TaskMode` (how
   a firing ends: `Recurring` — one turn — or `Goal` — repeated turns until a
   `StopCondition` is met). "Proactive" loops are `Interval`/`Once` + either mode, run
   unattended; not a fifth primitive.
2. **Local-only for v1.** Schedules run via `sophi schedule daemon` (an in-process
   coroutine ticker) or an OS-scheduler (`launchd`, via `sophi schedule install-launchd`)
   invoking the stateless `sophi schedule run-due`. A persistent server-hosted scheduler
   (so schedules survive the laptop being off) is a deferred future option, not built.
3. **Per-task destructive-tool allowlist.** There's no human to answer a confirmation
   prompt in an unattended run, so each task declares which specific DESTRUCTIVE tools
   it's pre-approved to call (`AllowlistConfirmationPolicy`); anything else DESTRUCTIVE
   is denied, same as today's non-interactive default.
4. **Isolation via the existing subagent pattern.** Each task run gets its own nested
   `AgentLoop` + fresh session, mirroring `SubagentTool` (ADR-007) rather than inventing
   a new isolation mechanism.
5. **Concurrent dispatch.** `ScheduleEngine.tickOnce()` runs all due tasks concurrently
   (bounded by a `Semaphore`), reusing `AgentLoop`'s existing parallel-tool-dispatch
   coroutine pattern — this is what delivers actual "parallel tasks." One task's
   exception is caught per-run and recorded as `RunOutcome.Failed`, so it never aborts
   the tick or other concurrently-running tasks.
6. **New module, not folded into `sophi-core`.** `sophi-schedule` depends on
   `sophi-core` + `sophi-ai` (needed for LLM-judged goal stop-conditions), preserving
   the invariant that `sophi-core` never imports `sophi-ai`.

## Reasons

1. **Two independent axes generalize better than four bespoke loop types.** Trigger
   answers "when," Mode answers "how a single firing ends" — every Claude Code loop
   type maps onto a Trigger/Mode pair without special-casing, and the two axes compose
   (e.g. a `Manual`-trigger `Goal`-mode task is `sophi goal run <id>`; an
   `Interval`-trigger `Goal`-mode task is a recurring retry-until-done job).
2. **Reuse over reinvention.** `SubagentTool`'s nested-`AgentLoop`-plus-fresh-session
   isolation and `AgentLoop`'s coroutine-based parallel dispatch were already proven; the
   scheduler is built as a second consumer of both patterns rather than a parallel
   implementation.
3. **Safety default matches existing precedent.** `sophi-web`/`sophi-sdk` already default
   to `ConfirmationPolicy.DENY_DESTRUCTIVE` for non-interactive contexts;
   `AllowlistConfirmationPolicy` is the same idea made per-task and opt-in, not a new
   risk posture.
4. **OS-scheduler-first avoids reinventing a scheduler.** `run-due` is deliberately
   stateless (load, run what's due, exit) so the actual timing authority is `launchd`/
   `cron` — battle-tested software Sophi doesn't need to reimplement. `daemon` exists
   purely as a zero-setup convenience wrapper around the identical call.

## Consequences

- Task creation is conversational (`manage_scheduled_task` Tool, registered in
  cli/web/sdk); inspection/management (`sophi schedule list/log/pause/resume/remove`,
  `sophi goal run`) is CLI-only for v1.
- `SophiRuntime` gained a `toolRegistry` constructor parameter and `toolNames()` accessor
  (sophi-sdk) so `RuntimeBuilder.schedule(dir)` opt-in tools can be verified by embedders
  — a small, backward-compatible (defaulted) surface addition alongside the existing
  `learning(config)` opt-in.
- Cron-expression triggers, true event-driven triggers, non-macOS notifiers, server-side
  hosting, and cross-task coordination are explicitly deferred — tracked in
  `TODO_TASK.md`.
- This also unblocks the "Proactive engine" follow-up previously parked under Jane's
  Palace's memory system, which was waiting on exactly this scheduling/notification
  infrastructure.

## References

- Spec: `docs/superpowers/specs/2026-07-21-scheduled-goal-tasks-design.md`
- Plan: `docs/superpowers/plans/2026-07-21-scheduled-goal-tasks.md`
- ADR-007 (subagent delegation) — isolation pattern reused here.
