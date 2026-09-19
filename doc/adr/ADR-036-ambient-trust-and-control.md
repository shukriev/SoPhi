# ADR-036: Ambient listening — trust & control

**Date:** 2026-09-19
**Status:** Accepted — implemented (manual UI verification of the sidebar indicator pending)

## Context

ADR-034 §5 deferred ambient listening's review surface with one sentence: "once tagging is
correct, browsing by provenance is the review UI." That does not hold. `provenance` is a proxy for
"overheard" that is wrong in both directions — a chat turn can produce `THIRD_PARTY` (as
`MemoryWriter.kt:65` notes for an unrelated reason: "provenance can be THIRD_PARTY even outside an
ambient turn"), and ambient speech in which the user talks about themselves correctly produces
`USER_DIRECT`.

Ambient writes are the highest-error-rate memories in the system — no diarization, no consent
mechanism, content-inferred attribution. Before ADR-034's remaining quality work (capture fidelity,
media discrimination, cost) could be judged, there had to be a way to see what ambient listening
actually stored.

## Decision

1. **`sourceSessionId` is the discriminator, and it was already persisted.** The companion writes
   ambient batches via `settleExternalTurn("ambient", ...)`, `MemoryWriter` stores
   `sourceSessionId = turn.sessionId`, and `PalaceStore` round-trips it. `BrowseFilter` gains a
   defaulted `sourceSessionId`, `JanesPalace.browse` one exact-match clause, and
   `JanesPalace.view` a `"source"` metadata key. No new column, no migration, no backfill.

2. **Forget became reversible.** `ForgetRequest.ById` gains a defaulted `soft: Boolean`. Before
   this, `ById` routed unconditionally to `forgetOne`/`store.deleteMemory` — permanent, as
   `ForgetEngine`'s class doc states — and `softDeletedAt` was written only by `Consolidator`, so
   `restore` could not undo a user's delete. Everything needed to *honour* a soft delete already
   existed (`Memory.active`, `purgeSoftDeleted`, `restore`, the `"state"` metadata key); the only
   missing piece was a way to set the flag. The default stays permanent, so the CLI's
   `memory forget` is unchanged; only the ambient review surface opts in.

3. **Reminder creation is announced by diffing `TaskStore`, not by parsing the reply.**
   `buildAmbientReminderPrompt` instructs the agent to "do nothing and reply with nothing" when
   there is no reminder, so the reply is not evidence. A new task id is. No new
   `NotificationKind` — `Schedule` already means "a scheduled task did something."

4. **`AmbientState` replaces the loop's private state.** `notifiedStartFailure` was a local `var`
   holding exactly the fact the user most needed surfaced. The loop now publishes
   `Off | Listening | Paused(untilMs) | Failed(reason)`, and the sidebar renders it. The one-shot
   notification is kept alongside the state for history.

5. **Every pause is time-boxed** (30 min / 1 hr / 8 hr), auto-resuming on the loop's own clock. An
   indefinite pause is deliberately absent: a listener silently off while the user believes it is
   on is worse than one that resumes unprompted, and indefinite off already exists as the
   `ambientListeningEnabled` Settings toggle, which is persisted and visible as a setting.

6. **CLI parity, closing ADR-034's own open item.** `sophi memory list` gains `--provenance` (the
   gap ADR-034's Consequences named) and `--source`; `/memory list` accepts both as suffix flags
   while the bare-room form keeps working.

## Consequences

- Thread compression creates a NARRATIVE summary with `sourceSessionId = "consolidation"` and
  soft-deletes the interior members it replaced, so a compressed ambient thread leaves the
  "overheard" view. `includeHidden` still reaches the originals. Merge is unaffected: it
  soft-deletes the absorbed duplicate and leaves the survivor's own `sourceSessionId` intact.
- An approval queue before memories land was considered and rejected — it needs pending state on
  `Memory`, a queue UI, and a policy for an unreviewed backlog. Browse-and-forget covers the need
  only because forget is now reversible; if decision 2 were reverted, this rejection would need
  revisiting.
- The sidebar indicator has no automated test — the project has no Compose UI test harness. Its
  inputs (`ambientState` transitions) are tested at the runtime level instead.
- No speaker diarization and no third-party consent mechanism. Both remain deliberate ADR-034 scope
  decisions; nothing here reopens them.

## References

- ADR-034 (ambient listening) — §5's review-surface claim is what this ADR corrects.
- ADR-028 (shared tool wiring) — the CLI/companion parity gap ADR-034 left open, now closed.
- ADR-014 (scheduled & goal-based tasks) — `TaskStore`/`manage_scheduled_task`, diffed rather than
  modified.
- Design spec and implementation plan:
  `docs/superpowers/specs/2026-09-19-ambient-trust-and-control-design.md` and
  `docs/superpowers/plans/2026-09-19-ambient-trust-and-control.md` — gitignored/local-only.
