# ADR-035: Commitment tracking (chat-only v1)

**Date:** 2026-09-11
**Status:** Accepted — implemented

## Context

Sophi already remembers facts passively (`UserProfile`, ADR-033's actionable patterns) but never
tracks a specific class of fact that matters more than most: a promise the user made ("I'll call
him back," "I need to renew my passport"). Nothing turned those into a follow-up. See the full
design rationale, including a rejected earlier design (a new `CommitmentStore` in `sophi-schedule`,
a short-cycle classifier timer, a new companion tab) and why it was rejected after adversarial
review, in `docs/superpowers/specs/2026-09-11-commitment-tracking-design.md` (gitignored,
local-only — the summary below is the durable record).

## Decision

1. **Classification happens once, at write time**, as one more judgment in
   `SignificanceEncoder`'s existing structured-output call (`VerdictMemory.commitment`) — not a
   new timer, not consolidation-time. A single commitment utterance is already complete evidence
   (unlike ADR-033's actionable patterns, which need repetition evidence and therefore do wait for
   consolidation).

2. **`Memory` gains one field, `isCommitment: Boolean`** (default `false`), set by `MemoryWriter`
   and gated in code to `provenance == Provenance.USER_DIRECT` — never ambient-sourced, regardless
   of what the encoder outputs. This is v1's chat-only scope, enforced structurally rather than
   left to prompt wording, because ambient listening has no speaker diarization (ADR-034) and a
   misattributed commitment would otherwise become an acted-on draft.

3. **No new store.** Commitments live in Jane's Palace like every other memory. This was a
   correction from the first design: a separate store outside `PalaceStore` would have bypassed
   `ForgetEngine`'s deletion propagation and lost ADR-033's unconditional sensitivity ceiling.
   `JanesPalace.openCommitments()` filters `active && isCommitment && sensitivity <=
   Sensitivity.PERSONAL && createdAt >= nowMs - commitmentExpiryMs` — same ceiling as
   `actionablePatterns()`, plus an age cutoff (`JanesPalaceConfig.commitmentExpiryMs`, default 30
   days) so an undismissed commitment stops being drafted instead of nagging forever. It remains
   visible via `browse()` past that point.

4. **One new tool, `list_open_commitments`** (`RiskLevel.SAFE`, read-only), registered in
   `RuntimeBuilder` wherever `list_actionable_patterns` already is.

5. **No new `TaskMode`, no new companion tab, no scheduling engine change.** As with ADR-033, the
   drafting/dismissal loop ships as an ordinary user-created `TaskMode.Goal` scheduled task: grant
   it `list_open_commitments` plus normal tool access (calendar, entity search), instruct it to
   draft a follow-up for each open commitment that doesn't already have a corresponding scheduled
   task, and have it call the existing `manage_scheduled_task` tool to create one with the draft as
   the task's description. Listing, viewing, and dismissal (pause/remove) are then free —
   `GoalsTab` already renders exactly this for any scheduled task. The task's prompt should
   instruct it to only cite facts from memories with `sensitivity <= Sensitivity.PERSONAL` and
   `provenance != Provenance.THIRD_PARTY` when drafting, and to reply with the sentinel
   `NO_RESCUE_NEEDED` when there's nothing new to draft, reusing `ScheduleEngine`'s existing
   notifier-suppression guard.

## Consequences

- Ambient-sourced commitments get no tracking or drafting in this iteration. They still benefit
  from a small, independent fix already shipped: `AmbientListener.REMINDER_KEYWORDS` now includes
  commitment-shaped phrases ("I'll," "I need to," "I have to," "I should," "I promise"), so an
  overheard commitment can still become a plain reminder task through the pre-existing ambient
  pipeline — just without `isCommitment` tagging or drafting.
- This is the second feature (after ADR-033) to depend on `NO_RESCUE_NEEDED`-style sentinel
  matching against free-form `PlanRunner` output, which has never been empirically verified against
  real runs. Worth verifying once, since two features now share the risk.
- Full ambient commitment tracking (detection + drafting) is a deferred follow-on, gated on
  ADR-034's pending verification and on a third-party consent mechanism that doesn't exist yet.
- `PalaceStore`'s hand-written ArcadeDB property mapping (`toProperties`/`toMemory`) has to be
  extended by hand for every new `Memory` field — `isCommitment` was initially missed here during
  implementation (caught by `openCommitments()`'s own test, which returned empty until the mapping
  was added) the same way any future `Memory` field addition risks silently round-tripping to its
  default. Worth keeping in mind for the next such field.

## References

- ADR-033 (personalized proactive rescue) — the pattern this mirrors for read-surface ceiling,
  scheduled-task shape, and notifier suppression.
- ADR-034 (ambient listening) — the diarization/consent gap that scopes this to chat-only.
- Design spec: `docs/superpowers/specs/2026-09-11-commitment-tracking-design.md` (gitignored).
