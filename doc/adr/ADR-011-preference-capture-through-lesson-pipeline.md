# ADR-011: Explicit + weighted implicit feedback, steering through the lesson pipeline

**Date:** 2026-07-09
**Status:** Accepted (implementation pending — spec: `docs/superpowers/specs/2026-07-09-learning-phase3-preference-feedback-design.md`)

## Context

Phase 3 aligns the agent with its user: preferences about style, format, and how much liberty to take. Two questions: how is feedback captured (explicit commands are precise but rare; implicit inference is free but noisy), and how does captured feedback steer future behavior — through the existing lesson mechanism from Phase 2, or a parallel preference-injection system?

## Decision

**Capture both channels, weighted.** Explicit `/good`/`/bad [reason]` (CLI and web) produce `weight: 1.0` records; implicit signals detected by the Phase 2 session-end evaluator (`user_corrected`, `user_rephrased`, `user_frustrated`, `user_satisfied`) produce `weight: 0.5` records that must carry a verbatim `evidence` quote or be dropped. Records are anchored to session entries by `sessionId + entryIndex` — content is never duplicated. Negative→positive retry sequences are linked (`pairedWith`) as future DPO pairs.

**No parallel steering mechanism.** Feedback distills — in the same session-end evaluator call — into lessons with `kind: "preference"`, flowing through the existing store → dedup → recall → injection pipeline. Differentiation is by rule, not machinery: explicit feedback can distill from one occurrence; implicit needs ≥ 2 corroborating records; preference lessons rank above all other lesson kinds when the recall token budget bites.

## Reasons

1. **Explicit-only starves; implicit-only hallucinates.** Users rarely grade turns, so explicit-only yields almost no data; implicit inference misreads rephrasing as displeasure often enough that it must not steer alone. Weights plus the corroboration threshold let the channels cover each other's weakness, and the mandatory evidence quote keeps every implicit record auditable after the fact.

2. **One pipeline, one transparency story.** Preferences reuse dedup, supersede, caps, injection, and the `sophi lessons` tooling from Phase 2 — nothing is built twice, and "what does Sophi believe about me" has a single answer surface. A parallel preference injector would drift from the lesson system in ranking, budgeting, and lifecycle.

3. **Anchoring by entry index makes feedback durable and joinable.** The session JSONL is already the source of truth; pointing at it (rather than copying response text into preference records) means Phase 4 can reconstruct full context for DPO pairs, and records stay small.

4. **Recording retry links now is nearly free; reconstructing them later is guesswork.** The (rejected, chosen) relationship is obvious at capture time — an explicit bad→good within a few turns, or an evaluator-observed retry — and is exactly the DPO raw material. Phase 3 stores only the link; all example construction stays in Phase 4.

5. **Deletion must actually delete.** Tombstoned feedback is excluded from distillation prompts and export; archived preference lessons are shown to the evaluator as "do not re-emit." Alignment data the user cannot revoke would be a trust failure.
