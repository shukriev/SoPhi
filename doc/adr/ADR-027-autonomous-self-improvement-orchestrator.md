# ADR-027: Autonomous self-improvement orchestrator (Phase 1)

**Date:** 2026-08-20
**Status:** Accepted — implemented

## Context

Sophi already accumulates operational history — tool reliability (`sophi-learning`'s
`ToolStatsStore`), session-end lessons (`LessonStore`, ADR-010), and per-task run outcomes
(`sophi-schedule`'s `RunLog`, ADR-014) — but nothing acts on it. There was no mechanism for
Sophi to look at its own history and propose a concrete improvement to itself.

This is Phase 1 of a broader autonomous self-improvement roadmap
(`docs/superpowers/specs/2026-08-18-autonomous-self-improvement-roadmap.md`, gitignored/local),
whose own two independent review passes converged on eight required deliverables before an
actual orchestrator task could be wired up safely: close a self-grant bypass discovered along
the way, make the `AgentDefinition` allowlist genuinely fail-closed and give both real
scheduled-task hosts (the CLI daemon, `sophi-companion`) a working copy of it simultaneously,
add attribution instrumentation and a wall-clock budget, and only then wire the orchestrator
itself plus a kill switch and a home for its findings. Sub-phases 0.5 (semantic lesson recall),
1a–1c shipped first; this ADR is written after the whole arc, at 1d, matching ADR-024's own
precedent of documenting spec-driven work retroactively rather than gating it.

Two rounds of independent code review (opus, applying a lazy-engineering/YAGNI lens) after
implementation caught real defects the design work itself missed — see Consequences.

## Decision

1. **The orchestrator is one more `ScheduledTask`, not new execution machinery.**
   `ScheduleEngine`'s `TaskMode.Goal` branch already drives `PlanRunner`/`TreePlanner` exactly
   per ADR-018/024. `OrchestratorWiring.bootstrapOrchestrator` creates a single task
   (`TaskMode.Goal(StopCondition.LlmJudged, maxIterations = 8)`, daily `Trigger.Interval`) —
   nothing downstream of task creation needed to change.

2. **Propose-only is enforced structurally, by tool risk tiers, not a new allowlist.**
   `toolGrants = emptySet()` on the orchestrator's task, combined with `ScheduleEngine`'s
   already-hardcoded `ConfirmationPolicy.DENY_ALL`, means only tools that classify as
   `RiskLevel.SAFE` run at all — every non-SAFE call is auto-denied with no override. Verified
   against every builtin tool's actual `riskLevel()` rather than assumed: `FileReadTool`,
   `GrepTool`, `GlobTool`, `GetCurrentDateTimeTool`, and `WebSearchTool` are SAFE; `FileWriteTool`,
   `EditTool`, `BashTool`, `RunClaudeCodeTool` — and, contrary to what its name suggests,
   `FetchUrlTool` — are all `DESTRUCTIVE`. The orchestrator gets read-only codebase and web
   research for free. An explicit `AgentDefinition`/`subagentType` allowlist (the mechanism
   decision 9 below makes fail-closed) was considered and rejected for v1 as unnecessary
   defense-in-depth: it would duplicate a boundary the risk-tier gate already provides, for a
   task with no `AgentDefinition` file of its own to maintain.

3. **`propose_improvement` + `ProposalPlugin`, not a stateful tool.** A `Tool` registered into
   `ScheduleEngine`'s shared `fullRegistry` cannot be bound to one session at construction time —
   that registry spans every task and every run, unlike `SubagentTool`'s interactive-session
   binding (ADR-007). `ProposeImprovementTool` stays trivial (`SAFE`, validates and
   acknowledges); capture happens via a `BEFORE_TOOL` hook (`ProposalPlugin`) reading
   `PluginRegistry.turnEventBridge`'s existing per-run `sessionId` and `argumentsJson` —
   `AFTER_TOOL`'s `HookContext` carries `toolResult`, not `argumentsJson`, so capture must
   happen at `BEFORE_TOOL`.

4. **`ProposalStore`: JSONL, `pending`/`accepted`/`rejected`, self-contained.** Schema:
   `Proposal(id, ts, sessionId, title, category, rationale, suggestedAction, status,
   reviewedAtMs, reviewReason)` — no `taskId` field; `RunRecord.sessionId` (decision 6) already
   makes the join recoverable if ever needed. `accept`/`reject` only transition a `pending`
   proposal (mirroring `LessonStore.archive()`'s own guard) and both mark `@Synchronized` on the
   store's mutators, appending a new line rather than mutating in place — a full audit trail,
   never deleted. Deliberately hand-rolls its own JSONL append/read (matching `RunLog`'s own
   shape) instead of depending on `sophi-learning`'s `JsonlLog` — `sophi-schedule` has no
   dependency on `sophi-learning` and one wasn't worth introducing for this.

5. **`sophi proposals list/accept/reject`: the v1 reviewer is a human, not an LLM.** Deliberately
   no automated or LLM-based proposal review — that would blur "propose-only" into "autonomous
   action" by another name. `ProposalsList`/`ProposalsAccept`/`ProposalsReject` mirror
   `LessonsCommand`'s existing plain-class-plus-thin-`CliktCommand` shape exactly.

6. **Kill switch fails toward OFF — the inverse of ADR-024's precedent.**
   `SOPHI_ORCHESTRATOR_ENABLED` must equal the literal string `"true"` (case-insensitive); unset,
   empty, misspelled, or `"false"` all mean disabled. `bootstrapOrchestrator` is idempotent both
   ways: flipping the switch toggles the existing task's `enabled` state rather than leaving
   stale state or duplicating the task. `SOPHI_TOT_SEARCH_ENABLED` fails toward ON because a
   probation review needs a typo to not silently zero out the measurement; an unattended
   research task that starts autonomously proposing changes needs the opposite default.

7. **`RunRecord.sessionId` — the missing keystone for attribution.** Before this, there was no
   way to trace a scheduled run back to the session — and thus the ADR-009 tool-round
   trajectory — it produced. `null` when no session was ever created (a wall-clock-budget or
   unresolved-`subagentType` skip fails before session creation).

8. **`LessonUsageEvent` closes the one real attribution gap; skills needed nothing new.**
   `contribute()`'s injected lesson context (ADR-013/Phase 0.5) is per-turn and never persisted
   to the session itself, unlike tool calls (ADR-009) — verified by reading
   `SophiRuntime.streamTurn()`, not assumed. `LessonsSection.render()` now logs
   `LessonUsageEvent(ts, scope, sessionId, lessonId)` per recalled lesson. Skill usage needed no
   new instrumentation at all: `SkillTool`/`sophi-cli`'s skill invocation is an ordinary tool
   call, already fully captured by ADR-009's tool-round session persistence.

9. **Per-task cumulative wall-clock budget, derived from data that already exists.**
   `ScheduledTask.maxWallClockMsPerWindow`/`wallClockWindowMs` (opt-in, default off) caps a
   task's own summed run duration within a trailing window — `taskTimeoutMs` already caps one
   run, but nothing capped cumulative time across many runs of a repeatedly-ticking task. Checked
   in `ScheduleEngine.runTask` before any session or provider call, summing `RunLog`'s existing
   `startedAtMs`/`finishedAtMs` — zero new persistence.

10. **Host wiring unified onto one path, not two independently-drifting ones.** `RuntimeBuilder`
    gained `agentsDir(dir, onWarning)`, threading `AgentDefinition`s into
    `SophiRuntime.scheduleEngine()` — closing `sophi-companion`'s allowlist gap for free, since it
    already calls that method. `sophi-cli`'s scheduler daemon (`ScheduleWiring.buildScheduleEngine`)
    stopped hand-constructing a bare `ScheduleEngine` and now routes through `Sophi.runtime{}`
    instead, gaining a real `PluginRegistry` signal path it never had. `ScheduleEngine` itself
    gained a fail-closed `subagentType` resolution (throws naming the task and the missing type,
    caught by the existing generic failure handler) — previously an unknown/missing
    `subagentType` silently fell back to the full unscoped registry.

11. **The self-grant-bypass fix, and its own residual gap, closed one file at a time.**
    `SubagentTool`'s `expected_tools`-to-`grants` conversion previously granted any declared tool
    name regardless of risk tier; fixed to filter to `RiskLevel.SAFE` only, mirroring
    `DecomposeGoalTool`'s pattern. Both call sites probe risk with placeholder arguments
    (`riskLevel("{}")`, since no real call arguments exist yet at declaration time) — which
    depends on every tool's `riskLevel` failing *closed*, not open, on unparseable input.
    `ScheduleTaskTool` was the only tool in the codebase that didn't (verified by checking every
    conditionally-risky tool's actual implementation); fixed to match its own `ruleVerdict`'s
    existing convention. The duplicated filter itself was later extracted to
    `ToolRegistry.safeGrantsFrom()`, with the fail-closed contract now stated once, in one doc
    comment, instead of implied at two call sites.

## Consequences

- Two rounds of independent opus code review after implementation, applying a lazy-engineering
  (YAGNI/duplication) lens, both caught real defects verified against actual source before being
  acted on: `ScheduleTaskTool`'s fail-open `riskLevel` (decision 11); `ProposalStore` missing
  `@Synchronized` on its write path; `sophi-web`'s `AgentController` silently losing lesson
  injection after Phase 0.5 narrowed `LearningPlugin.promptSections()` to reliability-only
  content, with no test covering that specific host; `LessonRecall.recall()` bridging a suspend
  embedding call via `runBlocking` from inside an already-running coroutine, and embedding
  cache-miss lessons one at a time instead of batching. All four were fixed and verified with
  new regression tests; the codebase's own established fail-closed philosophy (used throughout
  this phase) is what made the `ScheduleTaskTool` fix a one-line, low-risk change once found.
- Not addressed, and deliberately left as-is: `sophi-cli`'s scheduler daemon still never wires
  `.learning(...)` into its `Sophi.runtime{}` block, so scheduled tasks (including the
  orchestrator's own runs) never generate `LessonUsageEvent` data about themselves — the
  orchestrator can still read `lessons.jsonl`/`lesson-usage.jsonl` directly via its own
  `read_file`/`grep` access regardless, since that data comes from interactive sessions. The
  orchestrator also has no `subagentType`-scoped `AgentDefinition` (decision 2) — both are
  known, discussed tradeoffs, not oversights.
- `grants` membership, once granted via `safeGrantsFrom`'s empty-args probe, is trusted for
  every subsequent real call to that tool name for the rest of the nested session — it is never
  re-validated against the real call's own `riskLevel(call.argumentsJson)`. Not exploitable
  today (every shipped `Tool.riskLevel` either ignores its argument or fails closed on
  unparseable input), but the invariant is enforced by convention and one doc comment, not by a
  runtime check, test, or lint rule. A future `Tool` that returns `SAFE` for a missing/default
  field but `DESTRUCTIVE` for real arguments would reopen this class of bug silently.

## References

- ADR-007 (subagent delegation) — `SubagentTool`'s per-session binding, the reason a shared
  `ScheduleEngine` registry can't reuse that pattern (decision 3).
- ADR-009 (persist tool rounds) — the trajectory attribution instrumentation reuses directly for
  skills (decision 8); the reason lessons needed a new record and skills didn't.
- ADR-013 (memory as plugin context contributor) — `ContextContributor`, the interface
  `LessonUsageEvent` observes the effect of.
- ADR-014 (scheduled & goal-based tasks) — `ScheduledTask`, `RunLog`, `RunRecord`, all extended
  here rather than replaced.
- ADR-016 (tiered tool confirmation & grants) — `RiskLevel`, `ConfirmationPolicy`, `grants`; the
  safety mechanism decision 2 and decision 11 both depend on.
- ADR-018 (plan-and-execute) / ADR-024 (ToT widened replan) — `PlanRunner`/`TreePlanner`, reused
  unchanged by decision 1; ADR-024's retroactive-ADR and fail-toward-ON kill-switch precedents,
  both explicitly inverted or followed here.
- ADR-022 (sophi-companion) / ADR-023 (CLI hub) — the two real scheduled-task hosts decision 10
  unifies.
- ADR-026 (ArcadeDB memory storage) — unrelated in substance; noted because this branch was
  rebased onto its merge (PR #55) mid-implementation with no interaction (verified: zero
  references to `sophi-store` anywhere in this work).
- Roadmap, specs, plans, and both review transcripts: `docs/superpowers/specs/` and
  `docs/superpowers/plans/` — gitignored/local-only.
