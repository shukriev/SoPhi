# ADR-010: One session-end LLM self-evaluation; prompt-based lesson dedup; no vector database

**Date:** 2026-07-09
**Status:** Implemented (merged 2026-07-10, PR #3)

## Context

Phase 2 of the learning system needs judgment: did the session actually succeed, and what should be remembered? Mechanical signals (Phase 1) can't tell "tools ran fine but the approach was wrong." Options ranged from judging every turn with an LLM (rich but adds cost/latency to every interaction) to automatic-only (free but blind). Separately, storing and recalling distilled lessons raised the classic question: vector database (Chroma, FAISS) or not?

## Decision

**One LLM call per session, at session end**, after the final response is delivered. It does triple duty in a single strict-JSON contract: judges the outcome (`success|partial|failure` — the label Phase 4 filters training data by), distills reusable lessons, and deduplicates them (the prompt includes the scope's active lessons with "emit only what's new; supersede what's wrong"). Configurable cheaper model; one malformed-JSON repair retry; on failure, silent no-op — learning never breaks a session.

**No vector database.** Lessons live in append-only JSONL, capped at 50 active per scope, recalled by recency + use-count behind a `LessonRecall` interface. Embedding-based recall is a specced follow-up ("Phase 2.5"): an `EmbeddingProvider` contract in `sophi-ai` plus exact brute-force cosine in memory — still no external vector store.

## Reasons

1. **Session-end is the right cost point.** Per-turn judging multiplies LLM spend and adds user-perceived latency; session-end amortizes to one background call that also happens to be the only vantage point from which "did the *session* succeed" is even answerable. Phase 1's tool-round persistence (ADR-009) is what gives this call real evidence to judge.

2. **Chroma and FAISS solve the wrong problem here.** They exist for approximate nearest-neighbor over millions of vectors. This corpus is capped at ~50 lessons per scope — exact brute-force cosine over an in-memory list is microseconds and strictly more accurate than ANN. Adopting them would add a Python service dependency (Chroma) or JNI native packaging pain (FAISS) to a self-contained JVM codebase (ADR-003) for negative benefit at this scale.

3. **The real cost of semantic recall is the embedding model, not the index** — a new provider contract in `sophi-ai`, configuration, and per-write/per-query embedding calls. That is a meaningful scope increase with real benefit only once lessons accumulate, so it is deferred behind the `LessonRecall` interface (whose `query` parameter is already in the signature) rather than half-built now.

4. **Prompt-based dedup keeps one pipeline.** The evaluator already reads the active lessons for context; having it dedup/supersede in the same pass costs zero extra calls and keeps lesson lifecycle (emit, supersede, archive) in one auditable place. Caps keep the approach honest — dedup-by-prompt degrades if the lesson list grows unbounded, so it isn't allowed to.

5. **Kill switches and transparency are first-class.** `distillation=false` disables the call entirely; `sophi lessons list|archive` exposes everything learned. A learning system the user can't inspect or turn off is a liability, not a feature.
