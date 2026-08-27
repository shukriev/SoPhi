# ADR-031: Memory consolidation visibility, bulk recovery & audit consistency

**Date:** 2026-08-27
**Status:** Accepted — implemented

## Context

Phase 1.5 (ADR-029) and Phase 2 (ADR-030) both discovered their roadmap entries described a
codebase that no longer matched reality once checked against actual source. This phase's roadmap
entry followed the same pattern, more sharply: it framed `Consolidator.compress` as an
"unauthorized" autonomous mutation the orchestrator performs, with "no confirmation of any kind,"
needing to be "brought under Phase 1's authorization model and Phase 1.5's evaluation harness
retroactively."

None of that held up. `Consolidator.compress` already has a two-stage soft-delete-then-purge
lifecycle with a grace period (`JanesPalaceConfig.softDeleteGraceMs`) — only the later `purge` step
is physically irreversible, and it already has its own kill switch (`config.autoPurgeEnabled`).
`ForgetEngine.restore(id)` already exists and is already exposed as `sophi memory restore <id>` —
undo-before-purge already shipped end-to-end. `ConsolidationHistoryStore`/`consolidations.jsonl`
already logs every run's merged/strengthened/compressed/pruned/purged counts and the exact memory
ids affected — a real audit trail the orchestrator's own read-only research prompt already lists as
evidence it can inspect. Most decisively: the Phase 1 orchestrator's system prompt is explicit —
"You have no ability to modify Sophi, run shell commands, or write files — only to read and
research" — and it never calls `consolidate()` at all. Consolidation is ambient session-lifecycle
behavior, triggered by CLI session-exit and the companion's poll loop, firing in every
Jane's-Palace-enabled session regardless of whether an orchestrator is involved. "Bring this under
Phase 1's authorization model" did not name a real gap, because there was no orchestrator action
here to authorize.

## Decision

1. **`Consolidator`'s actual mutation logic is untouched.** This phase is additive around it, not a
   rewrite of it.

2. **The companion's previously-silent consolidation now notifies the user.**
   `CompanionRuntime.startSchedulePolling()`'s `consolidateIfDue()` call produced zero user-visible
   output before this phase, unlike the CLI's one-line session-exit summary. A small pure function,
   `consolidationNotificationBody(report): String?`, decides whether a run is worth surfacing
   (`null` for an all-zero report) and formats the summary — extracted as a standalone function
   specifically because `SophiRuntime`'s constructor is `internal` to `sophi-sdk`, so there was no
   seam to inject a fake `MemoryPlugin` into a `CompanionRuntime` test; the decision logic itself is
   unit-tested directly, while the one-line call site inside the poll loop stays covered only by the
   same best-effort `try`/`catch` the surrounding call already relies on.

3. **`sophi memory consolidations list [<id>]` browses history directly from
   `ConsolidationHistoryStore`** — it already has exactly the right typed shape
   (`ConsolidationRecord`), so this reads it directly rather than round-tripping through a
   JSON-encoded `Version.content`. Every parent command with subcommands in this codebase
   (`SkillCommand`, `VersionsCommand`, `TournamentCommand`, `ScheduleCommand`) takes zero arguments
   of its own — `list`/`show`/`revert` are always explicit subcommands, never a parent's bare
   invocation — so `consolidations` follows the same convention rather than a bare
   `sophi memory consolidations [<id>]`.

4. **`sophi memory consolidations restore <id>` performs bulk recovery** by iterating that run's
   `softDeletedIds` and calling the existing `ForgetEngine.restore(id)` (via `JanesPalace.restore`)
   per memory id — the same method `sophi memory restore <id>` already calls one id at a time. No
   new confirmation prompt: invoking this command is itself the confirmation, the same precedent
   `sophi memory restore <id>` already set.

5. **`sophi-versioning` gains `ArtifactType.MEMORY_CONSOLIDATION` for audit consistency, not as the
   recovery mechanism.** `ConsolidationRecord` gained a stable `id` field (defaulted for
   backward-compatible deserialization of pre-existing `consolidations.jsonl` lines — an old
   record's generated id isn't stable across re-reads until this phase's write path has run for it
   at least once, a one-time cosmetic inconsistency, not an ongoing correctness problem).
   `Consolidator` gained an optional `VersionStore?` parameter (the same nullable-dependency pattern
   Phase 1.5 gave `LessonStore`) and records exactly one `Version` per run. This makes a run
   inspectable via `sophi versions show MEMORY_CONSOLIDATION <id>` for consistency with every other
   artifact type — but `sophi versions revert MEMORY_CONSOLIDATION <id> <version-id>` does **not**
   perform real recovery: it would rewrite the audit record's stored JSON without touching
   `PalaceStore` at all. This limitation is stated directly in the command's own help text.

6. **The Phase 1.5 eval harness stays out of scope.** `EvalCase`/`runSuite` score task completion;
   "did this consolidation lose important information" is a different quality axis the existing
   harness shape doesn't fit. Naming that mismatch explicitly was judged better than building a
   check that only looks like it verifies something.

## Consequences

- The orchestrator's own scheduled-task host (`ScheduleDaemonCommand.kt`) doesn't currently wire
  `.memory(...)`, which is what resolves the roadmap's "co-blocked with Phase 0's
  shared-library-vs-shared-database decision" clause today — but nothing prevents a future change
  from adding memory support there without re-checking ADR-026's single-process ArcadeDB lock
  constraint. A one-line comment was left at that command's `run()` rather than a deeper
  investigation now, since no such change exists yet to investigate.
- `ProducedBy.REFLECTION` is reused for consolidation-run versions rather than adding a new enum
  value — a consolidation run is an automatic, non-human-authored artifact, the same category
  `REFLECTION` already covers for lessons.
- `sophi-web`'s tool wiring still doesn't route through any of this, matching the same
  already-noted gap from ADR-028, ADR-029, and ADR-030.

## References

- ADR-014 (scheduled goal tasks) — no new `ScheduleEngine` task type was introduced for periodic
  verification/browsing, following this same OS-scheduler-first precedent.
- ADR-024 (ToT widened replan) — the probation-discipline precedent behind treating an
  already-reversible mutation as sufficient without adding a new confirmation gate on top of it.
- ADR-026 (ArcadeDB memory storage) — the single-process-lock constraint this phase's Open Risk
  note (Consequences, above) exists to flag for future work, not to re-solve now.
- ADR-027 (autonomous self-improvement orchestrator) — its read-only, propose-only system prompt is
  the actual evidence that this phase's original "unauthorized orchestrator mutation" premise was
  false.
- ADR-029 (evaluation, versioning & config tournament substrate) — the `sophi-versioning` substrate
  this phase's `ArtifactType.MEMORY_CONSOLIDATION` extends, and the `LessonStore` nullable-dependency
  pattern this phase's `Consolidator` wiring mirrors.
- ADR-030 (skill-write admission gate) — the immediately preceding phase on this same branch, whose
  own roadmap entry was found similarly stale before implementation began, establishing the
  research-before-scoping discipline this phase followed.
- Design spec and implementation plan:
  `docs/superpowers/specs/2026-08-27-phase-3-memory-consolidation-visibility-design.md` and
  `docs/superpowers/plans/2026-08-27-phase-3-memory-consolidation-visibility.md` —
  gitignored/local-only.
