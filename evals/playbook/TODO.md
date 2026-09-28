# Playbook follow-ups (after the playbook PR merges)

Ordered by what unblocks what. Baseline: 2026-09-28, prism-ml/bonsai-27b on LM Studio. The details
are in the local, gitignored `runs/2026-09-28-baseline.md`.

## 1. Fix the /goal judge — Sophi bug, root cause confirmed (do first)

`PlanRunner.judge` (`sophi-core/.../agent/plan/PlanRunner.kt:355-365`) asks "Answer with exactly one
word: YES or NO" with `maxTokens = 8`. Thinking models (bonsai-27b, qwen3.5) spend those 8 tokens
reasoning: `finish_reason=length`, empty content, so the judge can never say YES.
`checkStopCondition` is false after every completed plan (line 151), so the runner replans until
`maxReplans` (157-162) and ends `Exhausted`. Each replan re-sends the task prefixed with "Retry: ",
which repeats non-idempotent work.

- Evidence: 5 real sessions (qwen3.5:9b), playbook plan-1 and plan-3, and plan-4 running 30+ min.
  Same prompt on bonsai-27b: `max_tokens=8` → `length`, `''`; `max_tokens=400` → `stop`, `YES`.
- [x] Give the judge room to think: `maxTokens = 1024` (fix/playbook-followups).
- [x] Regression test: `PlanRunnerTest` — "the LlmJudged judge gets enough tokens for a thinking model to reach its YES".
- [x] Other `LlmJudged` users: `PlanRunner.judge` is the only evaluation site, so `/goal`, sub-plans and
      scheduled goal tasks are all covered. The same starvation was also found in
      `Consolidator` compress (200 tokens, no `reasoningEffort`), where an empty summary soft-deleted
      a memory thread. Fixed, with a test: "compress keeps the thread when the summary comes back blank".

## 2. First A/B runs (README → "A/B a prescription")

Run each only after item 1 lands; until then /goal replans are noise that swamp everything.

- [ ] **coding-3 — symptom patching.** Failed twice with the same `int(frac or "0")` patch and no
      test run. Candidate lesson (`approach`): "After fixing a crash, run the test suite before
      reporting done; fix the parsing so every input parses correctly, not just the one that
      crashed." A/B on coding-1…4.
- [ ] **plan-1 / tool-1 — write_file misuse.** Overwrote CHANGELOG when asked to append; answered
      in chat instead of writing the file. Candidate lesson (`tool_usage`): "Append with bash `>>` or
      edit_file; write_file replaces the file. When asked to write an answer to a file, write it —
      don't only say it." A/B on tool-1…5.
- [ ] **plan-2 — doesn't ask first.** It invented a folder layout from "Set up the reports folder".
      Probably a `prompt` fix. Needs more than 1 valid run before choosing.

## 3. Case fixes

- [x] **coding-4 is a case-bug.** bonsai-27b solved it alone in both runs, so it doesn't need
      delegation. Enlarged: multi-currency support (EUR conversion half-up, `--by-currency`) plus
      the package split, with a hidden test. Needs a live run.
- [ ] **plan-4** has no valid run yet (the judge bug made it spiral). Re-run after item 1 before
      judging it.
- [x] **`glob "**/ledger/**"` → No files found** (tool-1 ×2, coding-4, where it tripped the loop
      guard). Confirmed: Java `PathMatcher` makes `**/` consume at least one directory. `GlobTool` now
      also tries each zero-directory reading; tested (`GlobToolTest`).

## 4. Remaining baseline coverage

- [ ] Create a Calendar.app calendar named `Sophi Eval` (setup wipes it every run), then run
      assistant-1.
- [ ] Export `BRAVE_SEARCH_API_KEY`, then run assistant-4.
- [ ] Get every case to 3 valid runs. Most have 1–2, and one run proves nothing on a local model.

## 5. Environment

- [ ] **LM Studio `text-embedding-qwen3-embedding-0.6b` is broken** ("failed to decode, ret = -3",
      even after a reload; nomic still works). The companion uses it, so companion memory is
      probably off. Check the companion log for "memory: disabled". Also fix the `qwe3` → `qwen3`
      typo in the companion profile if it's there.
- [x] `run.sh` defaults to nomic embeddings: each run has an empty store, so the playbook doesn't
      depend on the companion's embedding model.

## 6. Later

- [ ] Harvest more cases from real failures in `~/.sophi/sessions` as they happen: this is the best
      source. Generalise the prompts; never copy real data into `cases.sh`.
- [ ] Phase B (weights) only once harness fixes stop moving the pass rate. It needs its own spec and a
      harvested corpus of hundreds of runs; 19 hand-written cases won't do.
