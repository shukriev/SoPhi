# ADR-012: Offline trajectory export to chat-JSONL SFT and conversational DPO; training out of scope

**Date:** 2026-07-09
**Status:** Accepted (implementation pending — spec: `docs/superpowers/specs/2026-07-09-learning-phase4-trajectory-export-design.md`)

## Context

Phase 4 of the learning system serves the "improve the model itself" ambition. True reinforcement learning (policy-gradient weight updates against a reward signal) is infeasible against hosted providers and lab-sized even for local models. The realistic path is supervised: harvest the agent's own labeled history into fine-tuning datasets for the local-model provider. Decisions needed: where the pipeline runs, what formats it emits, and where the phase's responsibility ends.

## Decision

A standalone **`sophi export`** CLI command (implementation in `sophi-learning`), reading only files earlier phases already write: session JSONL + sidecars (trajectories with tool rounds and config snapshots, per ADR-009), outcome judgments (ADR-010), and preference records with retry links (ADR-011).

It emits two dataset files plus provenance:

- **`sft.jsonl`** — one chat-format `{"messages": [...]}` example per session judged `success` (with `tool_calls` / `tool`-role messages), excluding sessions carrying negative feedback and subagent sessions.
- **`dpo.jsonl`** — one TRL-style conversational `{"prompt", "chosen", "rejected"}` example per Phase 3 retry link, with the user's intervening correction excised from `prompt`; unpaired negatives are counted but never exported.
- **`manifest.json`** — filters, counts, redaction tallies, source session ids: every example auditable back to its session.

Redaction (built-in secret/PII patterns + user-extendable list) is on by default. Deterministic dedup and train/eval split by session-id hash. **Training itself — GPU orchestration, TRL/axolotl invocation, model registry, evals — is explicitly out of scope**, deferred to a future phase with its own design.

## Reasons

1. **Datasets are the durable asset; trainers churn.** Chat-messages JSONL and TRL conversational DPO are the intersection accepted by TRL, axolotl, unsloth, and llama-factory today — and the labeled sessions remain re-exportable to whatever format wins later. Stopping at files keeps this phase small, testable with golden files, and immediately useful.

2. **Offline command over runtime pipeline.** Export needs no live signals — everything it reads is already on disk (the surviving idea from the rejected "offline mining only" architecture in ADR-008). Zero runtime coupling means zero risk to the agent loop and no always-on cost.

3. **Labels are what make the data worth training on.** Filtering SFT by ADR-010's judgment and excluding negatively-marked sessions is the difference between "imitate everything I ever did" and "imitate what worked." The DPO prompt-excision rule exists for the same reason: a pair where the correction leaks into the prompt teaches nothing.

4. **Redaction on by default.** Trajectories contain the user's working life — keys, paths, private text. A dataset that might be shared or uploaded later must be scrubbed at creation, not remembered about afterwards; opting *out* requires interactive confirmation.

5. **Provenance is non-negotiable.** Fine-tuning failures are debugged through data lineage; the manifest makes every dataset reproducible and every example traceable to its source session.
