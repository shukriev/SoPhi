# ADR-013: Declarative memory as a plugin contributing per-turn context

**Date:** 2026-07-14
**Status:** Implemented

## Context

Sophi has procedural memory (sophi-learning: lessons, tool stats, preferences) but no
declarative memory — nothing remembers the user's world across sessions. The Jane's Theory
experiment (spec: docs/superpowers/specs/2026-07-13-janes-theory-memory-design.md) adds a
memory-palace system: salience computed at encoding, five typed rooms with distinct decay
half-lives, causal narrative links, blended retrieval, true deletion.

Three placements were considered:

- **A.** A new `sophi-memory` module behind a technique-agnostic SPI, integrating as a
  `SophiPlugin` — with one new SPI capability in `sophi-extensions`, because memory recall
  is per-turn and query-dependent while the existing hook system is fire-and-forget
  observation and `promptSections()` is evaluated once at startup.
- **B.** Direct wiring: `sophi-cli` calls the memory module explicitly each turn.
- **C.** Memory inside `sophi-core` next to `ContextCompactor`.

## Decision

Approach A. `sophi-extensions` gains `ContextContributor` (`suspend fun contribute(sessionId,
userInput): String?`) plus `PluginRegistry.collectContext(...)` — per-plugin timeout,
failures swallowed, but `CancellationException` is rethrown so caller cancellation is never
absorbed. `sophi-memory` holds the neutral `MemoryTechnique` SPI and its first
implementation `JanesPalace`; `MemoryPlugin` adapts it (contribute → recall; AFTER_TURN →
fire-and-forget encode). `HookContext` gains `assistantReply` so encoders see both sides of
a turn. Embeddings enter via a new `EmbeddingProvider` in `sophi-ai` (plain-HTTP OpenAI-compat
`/v1/embeddings`). Memory storage is user-global (`~/.sophi/memory/`), unlike learning's
scope-tagged store — a person's life context is not per-repository.

True deletion is more than a tombstone: `ForgetEngine.forgetOne` re-links the causal chain
around the removed memory, reduces profile evidence, and now also scrubs
`last-recall.txt` so a deleted memory cannot resurface through the explain path. The forget
CLI shows the blast radius before the user commits to it — `ForgetEngine.preview(id)` /
`JanesPalace.previewForget(id)` compute the same impact (removed id, relinked-edge count,
affected profile paths) without mutating anything, so `sophi memory forget` can confirm
before it deletes.

## Reasons

1. **Core stays minimal** (the ADR-008 argument, again): `sophi-core` remains
   memory-free; C was rejected outright because core cannot import `sophi-ai`
   (dependency rule), so it could never call embeddings or the encoder.
2. **The harness gains a reusable seam, not a special case.** B was rejected because it
   makes memory a privileged friend of each surface; `ContextContributor` is generic —
   any future plugin (RAG, calendars, alternative memory techniques) injects per-turn
   context identically, and wiring web/sdk later is mechanical.
3. **Technique-agnostic SPI with one real implementation.** The SPI is shaped by
   JanesPalace's concrete needs (recall/observe/consolidate/forget/browse/profile), not
   speculation; a substitutability test keeps it honest.
4. **True deletion breaks append-only deliberately.** User-initiated forget is a
   compacting rewrite (re-linked causal chain, reduced profile evidence, scrubbed
   last-recall state), because "deleted means gone" is incompatible with tombstone
   events — a scoped exception to ADR-005's append-only philosophy, contained in
   `PalaceStore`. Previewing the impact before committing to it keeps that irreversible
   operation honest without weakening the deletion guarantee itself.
