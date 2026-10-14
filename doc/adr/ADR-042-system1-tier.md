# ADR-042: System-1 tier — a fast model for Sophi's small judgments

**Date:** 2026-10-14
**Status:** Accepted — sub-project 1 (routing, shadow, decision log) implemented

## Context

Sophi makes about a dozen small structured judgments per session — tool risk, step and plan
critique, goal judging, session evaluation, memory encoding and consolidation, skill security,
workflow reflection, and three summaries — each on the same large model as the main turn. On
thinking models (bonsai-27b, qwen3.5) these keep failing: the model spends the token budget
reasoning and returns nothing, which is why `reasoningEffort = "none"` has been hand-added to call
after call. The long-term goal is Sophi's own calibrated small model (distilled from a teacher)
that answers these fast and escalates to the large model when unsure; that needs real paired
data first.

## Decision

1. **A judgment tag, not a new call path.** `CompletionRequest.judgment` names the judgment
   (`Judgments`); untagged calls are not judgments.
2. **A provider decorator.** `DualSystemProvider` wraps the configured (System-2) provider and
   routes tagged `complete()` calls by per-judgment mode: `SYSTEM2` (today), `SHADOW` (S2 answers;
   S1 runs afterwards in the background, one at a time, and is only logged), `SYSTEM1` (S1 answers,
   falls back to S2 on error or an empty reply). `stream()` and untagged calls always go to S2.
3. **One settings file for every host.** `~/.sophi/system1.json` (endpoint, model, modes, sample
   rates), read through `system1FromHome` by the CLI's `buildProvider` and `RuntimeBuilder.system1()`.
   No file: the provider is returned unchanged.
4. **Safety is one-way.** `risk` and `skill-security` cannot be `SYSTEM1` (clamped to `SHADOW`).
5. **A versioned decision log.** `~/.sophi/system1/decisions.jsonl`, schema v1, full request plus
   both replies; outcome rows (`kind: "outcome"`) are appended and folded later, never rewritten.

## Consequences

- Off by default; shipping it changes no behaviour.
- `SHADOW` doubles judgment compute on the S1 machine; `shadowSampleRate` bounds it.
- `AUTO` mode (confidence-thresholded escalation) and typed `Judgment<I, O>` are deferred to the
  measurement sub-project — no calibration data exists yet.
- The trained model's weights are out of scope here and stay closed initially; this repo holds
  only the open framework.
