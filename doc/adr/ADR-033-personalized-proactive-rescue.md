# ADR-033: Personalized proactive rescue

**Date:** 2026-09-10
**Status:** Accepted — implemented

## Context

`sophi-schedule` can already run unattended tasks, and Jane's Palace (`sophi-memory`) can already
remember recurring personal facts. Nothing tied them together: a memory like "the user always
forgets their passport before a flight" and a calendar event "flight to NYC tomorrow" both exist
inside Sophi, but nothing cross-referenced them. This is not generic deadline surfacing — any
calendar app does that — it's a rescue tied to a pattern specific to this user, arguably the
payoff the memory system was built toward.

The first design considered tagging a memory as an "actionable pattern" during per-turn encoding
(`SignificanceEncoder`'s existing structured-output call). That was rejected during design review:
a single utterance ("ugh, forgot my passport again") is evidence of one incident, not a recurring
pattern. Real repetition evidence only exists once a fact has recurred across multiple
turns/episodes — which `Consolidator` already tracks via `SalienceSignals.rep`, computed by
`MemoryWriter` from similarity to recent memories in the same room. Classification moved to
consolidation time instead.

A second review point: the read surface for tagged patterns feeds directly into an unattended
scheduled task whose output becomes an OS notification banner — a more exposed surface than a
chat reply (visible on a lock screen, to anyone nearby). This forced an unconditional sensitivity
ceiling on the read surface rather than a caller-configurable one.

## Decision

1. **`Memory` gains an `actionablePattern: Boolean` field** (default `false`, so existing stored
   rows keep decoding without migration).

2. **Classification happens in `Consolidator`, not `SignificanceEncoder`.** A new
   `classifyPatterns(nowMs)` step runs after `merge`/`strengthen`: candidates are active memories
   with `signals.rep >= config.patternRepThreshold` (new `JanesPalaceConfig` field, default `0.5`)
   and not yet tagged. One batched LLM call — same shape as `compress()`'s per-thread call, not one
   call per candidate — classifies the whole candidate shortlist at once. Failure is non-fatal
   (`runCatching`, same as `compress()`): a broken classification call skips this step only: the
   rest of `run()` (merge/strengthen/prune/purge) completes normally.

   This also resolves retroactivity for free. Consolidation scans all active memories every cycle,
   and `rep` was already computed and stored at write time before this feature existed — a
   genuinely repeated historical pattern already carries the evidence it needs and gets classified
   on the next consolidation run. A fact mentioned only once, past or future, correctly never
   qualifies.

3. **`JanesPalace.actionablePatterns()` is the read surface**, filtering to
   `active && actionablePattern && sensitivity <= Sensitivity.PERSONAL`. The sensitivity ceiling is
   unconditional, not a parameter — `SENSITIVE`/`RESTRICTED` patterns never reach it regardless of
   how the caller asks, because this feed's output reaches a notification banner.

4. **One new tool, `list_actionable_patterns`**, wraps that read surface. `RiskLevel.SAFE`
   (read-only). `RuntimeBuilder.build()` registers it into the tool registry whenever memory is
   successfully configured (`memoryPlugin?.palace()?.let { registry.register(...) }`), the same
   place `ScheduleTaskTool` is registered — no separate CLI-side wiring needed.

5. **No new `TaskMode` or scheduling engine change beyond one notifier guard.** This ships as an
   ordinary user-created `TaskMode.Goal` scheduled task: `toolGrants` includes the new tool plus
   the existing calendar read tools (`list_calendar_events`, `get_calendar_event`); the task's
   prompt instructs the agent to cross-reference patterns against upcoming events itself and reply
   with the exact sentinel `NO_RESCUE_NEEDED` when nothing applies. `ScheduleEngine`'s single
   `notifier.notify(task, record)` call site gets one guard: skip when
   `record.summary.trim().equals("NO_RESCUE_NEEDED", ignoreCase = true)`, so a daily check doesn't
   ping for nothing. A `Failed`/timed-out run is never suppressed by this guard — only the exact
   sentinel is silenced.

## Consequences

- `ConsolidationReport`/`ConsolidationRecord` both gained a `classified: Int` field (appended as
  the last constructor parameter, defaulted to `0`) so every existing positional call site kept
  compiling unchanged. The CLI's session-exit consolidation summary line now also prints
  `classified=N`.
- Sentinel matching is deliberately lenient (trim + case-insensitive), not exact-string, since
  `PlanRunner`'s `finalOutput` is free-form LLM text. Whether the model reliably emits the bare
  sentinel with no stray punctuation has not been empirically verified against real runs yet —
  flagged rather than over-built around.
- This only ships wherever `RuntimeBuilder` already wires memory + scheduling, i.e. `sophi-cli`
  today. `sophi-web`'s separate, still-incomplete memory wiring (tracked in `TODO_TASK.md`) is out
  of scope.
- Event-driven wake (reacting the instant a calendar event is created, rather than on a periodic
  scan) remains a separate, already-tracked TODO item — `CalendarProvider` is a passive
  read/write interface with no watch/sync on the user's native calendar app.

## References

- ADR-026 (ArcadeDB memory storage) — `PalaceStore`'s single-process directory lock, which is why
  a test seeding fixture data must close its `PalaceStore` before `JanesPalace` opens the same
  `home` directory.
- ADR-031 (memory consolidation visibility) — the `Consolidator`/`ConsolidationRecord` surface this
  phase extends with one more step and one more count field, following the same
  audit-trail-first precedent.
- Design spec and implementation plan:
  `docs/superpowers/specs/2026-09-10-personalized-proactive-rescue-design.md` and
  `docs/superpowers/plans/2026-09-10-personalized-proactive-rescue.md` — gitignored/local-only.
