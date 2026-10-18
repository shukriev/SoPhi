# Context engine — retrieval results

| Date | Commit | Set | n | hit@1 | hit@5 | hit@10 | MRR | grep MRR | p50/p95 ms |
|---|---|---|---|---|---|---|---|---|---|
| 2026-10-12 | ab4177c | queries.tsv | 31 | 0.71 | 0.94 | 0.97 | 0.80 | 0.12 (6.7x) | 13.1 / 22.6 |
| 2026-10-12 | ab4177c | commits.tsv | 258 | 0.53 | 0.76 | 0.83 | 0.64 | 0.26 (2.4x) | 13.6 / 21.8 |

Exit criteria (spec §10 M1): queries.tsv hit@10 >= 0.90 and MRR >= 0.80 — met; commits.tsv engine MRR >= 2x grep — met (2.4x).

Weights: k1 1.2, b 0.5, nameBoost 1.5, perDirectory 3, candidates 300, rolePrior SOURCE 1.0 / INSTRUCTION 1.0 / DOC 0.9 / CONFIG 0.9 / TEST 0.6 / GENERATED 0.2.
(queries.tsv guided the weights, so its numbers are slightly optimistic. commits.tsv was observed while tuning but not optimised against; it is only a sanity check, not a clean held-out set.)

k1/b decision: the earlier pick, k1 0.9 / b 0.4, scored queries.tsv hit@10 0.97 / MRR 0.82 and commits.tsv hit@10 0.82 / MRR 0.63. k1 1.2 / b 0.5 scores queries.tsv hit@10 0.9677 / MRR 0.80242 (unrounded; the rule was MRR >= 0.80 and hit@10 >= 0.90) and commits.tsv hit@10 0.83 / MRR 0.64. Relative to the old defaults (1.2 / 0.75), the earlier pick moved commits.tsv hit@10 0.84 -> 0.82 with MRR flat at 0.63; 1.2 / 0.5 gives back part of it (0.83) at MRR 0.64. It clears the exit criteria by a thin MRR margin (0.0024) on queries.tsv, so the 31-query set cannot tell the two choices apart; commits.tsv (258 queries) favours 1.2 / 0.5, hence the switch.

Variants tried (nameBoost : perDirectory : priors : k1 : b — queries hit@10 / MRR ; commits hit@10 / MRR):
| Variant | queries | commits |
|---|---|---|
| 1.0 : 100 : none : 1.2 : 0.75 (bake-off baseline; bake-off itself reported 0.90 / 0.80) | 0.94 / 0.79 | 0.80 / 0.62 |
| same, candidates 1000 | 0.94 / 0.79 | 0.81 / 0.62 |
| 1.0 : 100 : none : 1.5 : 0.75 | 0.94 / 0.78 | 0.80 / 0.62 |
| 1.0 : 100 : none : 0.9 : 0.4 | 0.94 / 0.79 | 0.80 / 0.61 |
| 1.0 : 100 : none : 1.2 : 0.5 | 0.90 / 0.77 | 0.80 / 0.61 |
| 1.0 : 100 : none : 2.0 : 0.75 | 0.97 / 0.76 | 0.81 / 0.62 |
| 1.5 : 3 : default : 1.2 : 0.75 (old defaults) | 0.97 / 0.78 | 0.84 / 0.63 |
| 1.5 : 100 : default : 1.2 : 0.75 | 0.97 / 0.78 | 0.83 / 0.63 |
| 1.0 : 3 : default : 1.2 : 0.75 | 0.94 / 0.78 | 0.83 / 0.62 |
| 1.5 : 3 : TEST 1.0 : 1.2 : 0.75 | 0.97 / 0.76 | 0.81 / 0.65 |
| 1.5 : 5 : TEST 0.8 : 1.2 : 0.75 | 0.97 / 0.77 | 0.81 / 0.64 |
| 1.2 : 5 : TEST 0.8 : 1.2 : 0.75 | 0.90 / 0.77 | 0.81 / 0.64 |
| 2.0 : 3 : default : 1.2 : 0.75 | 0.97 / 0.78 | 0.82 / 0.62 |
| 1.5 : 3 : default : 1.5 : 0.75 | 0.97 / 0.77 | 0.84 / 0.64 |
| 1.5 : 3 : default : 1.2 : 0.5 | 0.97 / 0.80 | 0.83 / 0.64 |
| 1.5 : 3 : default : 0.9 : 0.4 (previous pick) | 0.97 / 0.82 | 0.82 / 0.63 |
| **1.5 : 3 : default : 1.2 : 0.5 (chosen)** | **0.97 / 0.80** | **0.83 / 0.64** |

The no-priors baseline lands at 0.94 / 0.79 rather than the bake-off's 0.90 / 0.80: the repos have changed since the bake-off (files added, symlinks and secret files now skipped), so exact reproduction is not expected.

Reproduce: `sophi context eval --project SoPhi=<path> --project sophi-companion=<path> --queries evals/context/<set>.tsv`.

## Hint gate (offline E1)

Pre-registered selection rule (written before any grid was run):

- Grid: minScore in {0, 2, 4, 6, 8, 10, 12, 15} x minRatio in {1.0, 1.1, 1.25, 1.5, 2.0}; gate = HintGate(minTerms 3, minScore, minRatio) on the top-3 hits.
- A fired hint is correct if any of its (up to 3) files matches an expected substring. coverage = fired / n; precision = correct / fired (1.0 when nothing fired).
- Select the point with the highest coverage among those with precision >= 0.80 on queries.tsv AND precision >= 0.70 on commits.tsv. Ties: higher precision on commits.tsv.
- Coverage for the selection is read from queries.tsv. If no point qualifies, the gate stays closed by default (hint mode default becomes `off`). The rule is not loosened and the queries are not edited.

## Hint gate results (E1, offline)

Corpus: SoPhi and sophi-companion main checkouts indexed into a temp store. Raw output of `sophi context eval ... --hint-grid` (top-3 hits, minTerms 3, identifier bypass included in the gate).

queries.tsv:
```
n=31  top-1 score min=11.11 median=29.59 p90=42.69 max=55.17
minScore minRatio coverage precision
     0.0     1.00    1.000     0.839
     0.0     1.10    0.839     0.846
     0.0     1.25    0.516     1.000
     0.0     1.50    0.290     1.000
     0.0     2.00    0.194     1.000
     2.0     1.00    1.000     0.839
     2.0     1.10    0.839     0.846
     2.0     1.25    0.516     1.000
     2.0     1.50    0.290     1.000
     2.0     2.00    0.194     1.000
     4.0     1.00    1.000     0.839
     4.0     1.10    0.839     0.846
     4.0     1.25    0.516     1.000
     4.0     1.50    0.290     1.000
     4.0     2.00    0.194     1.000
     6.0     1.00    1.000     0.839
     6.0     1.10    0.839     0.846
     6.0     1.25    0.516     1.000
     6.0     1.50    0.290     1.000
     6.0     2.00    0.194     1.000
     8.0     1.00    1.000     0.839
     8.0     1.10    0.839     0.846
     8.0     1.25    0.516     1.000
     8.0     1.50    0.290     1.000
     8.0     2.00    0.194     1.000
    10.0     1.00    1.000     0.839
    10.0     1.10    0.839     0.846
    10.0     1.25    0.516     1.000
    10.0     1.50    0.290     1.000
    10.0     2.00    0.194     1.000
    12.0     1.00    0.968     0.833
    12.0     1.10    0.839     0.846
    12.0     1.25    0.516     1.000
    12.0     1.50    0.290     1.000
    12.0     2.00    0.194     1.000
    15.0     1.00    0.968     0.833
    15.0     1.10    0.839     0.846
    15.0     1.25    0.516     1.000
    15.0     1.50    0.290     1.000
    15.0     2.00    0.194     1.000
```

commits.tsv:
```
n=258  top-1 score min=5.52 median=26.63 p90=54.21 max=90.24
minScore minRatio coverage precision
     0.0     1.00    0.988     0.733
     0.0     1.10    0.733     0.767
     0.0     1.25    0.574     0.784
     0.0     1.50    0.419     0.870
     0.0     2.00    0.360     0.882
     2.0     1.00    0.988     0.733
     2.0     1.10    0.733     0.767
     2.0     1.25    0.574     0.784
     2.0     1.50    0.419     0.870
     2.0     2.00    0.360     0.882
     4.0     1.00    0.988     0.733
     4.0     1.10    0.733     0.767
     4.0     1.25    0.574     0.784
     4.0     1.50    0.419     0.870
     4.0     2.00    0.360     0.882
     6.0     1.00    0.988     0.733
     6.0     1.10    0.733     0.767
     6.0     1.25    0.574     0.784
     6.0     1.50    0.419     0.870
     6.0     2.00    0.360     0.882
     8.0     1.00    0.988     0.733
     8.0     1.10    0.733     0.767
     8.0     1.25    0.574     0.784
     8.0     1.50    0.419     0.870
     8.0     2.00    0.360     0.882
    10.0     1.00    0.981     0.735
    10.0     1.10    0.733     0.767
    10.0     1.25    0.574     0.784
    10.0     1.50    0.419     0.870
    10.0     2.00    0.360     0.882
    12.0     1.00    0.969     0.744
    12.0     1.10    0.725     0.775
    12.0     1.25    0.574     0.784
    12.0     1.50    0.419     0.870
    12.0     2.00    0.360     0.882
    15.0     1.00    0.868     0.768
    15.0     1.10    0.698     0.789
    15.0     1.25    0.566     0.788
    15.0     1.50    0.415     0.869
    15.0     2.00    0.360     0.882
```

Top-1 BM25 score distribution:

| Set | n | min | median | p90 | max |
|---|---|---|---|---|---|
| queries.tsv | 31 | 11.11 | 29.59 | 42.69 | 55.17 |
| commits.tsv | 258 | 5.52 | 26.63 | 54.21 | 90.24 |

Selected point: **minScore 10.0, minRatio 1.0**.

| Set | coverage | precision |
|---|---|---|
| queries.tsv | 1.000 | 0.839 |
| commits.tsv | 0.981 | 0.735 |

Trace of the rule: qualifying needs precision >= 0.80 on queries.tsv and >= 0.70 on commits.tsv. At minRatio 1.1 and above, queries.tsv coverage is <= 0.839, so those points lose to coverage 1.000. At minRatio 1.0, minScore 0-10 all reach queries.tsv coverage 1.000 (precision 0.839) with commits.tsv precision 0.733 (0-8) or 0.735 (10), so all qualify and tie on coverage. minScore 12 and 15 fall to 0.968 coverage and are out. Tie-break is higher commits.tsv precision: 0.735 at minScore 10 beats 0.733, so minScore 10.0 / minRatio 1.0 is selected. Defaults in `HintGate` are set to these values.

Limits: the grid's score range (0-15) sat below the real top-1 distribution (median 27-30), so minScore <= 10 barely filters and minRatio 1.0 always passes; the chosen gate is effectively "at least 3 content terms and top score >= 10". Both query sets contain only real questions, with no chit-chat or off-topic negatives, so offline E1 cannot measure false fires. Roughly 1 in 6 (queries.tsv) to 1 in 4 (commits.tsv) fired hints point at a wrong file. The live E1 (deferred to the end of the work) decides whether the hint helps, and should include negative messages.

## Live E1/E5 (small local model, 2026-10-14)

Setup: `sophi-cli` at b5a34f8 (merged M0–M3), model `prism-ml/bonsai-27b` on LM Studio (32k context), runner
`sophi-arena/context_qa` (arena commit a9a0471). 23 repo-QA questions over a frozen SoPhi snapshot: the
SoPhi-only queries of `queries.tsv`, asked as "find the file(s) that answer it, end with FILES:"; success = the
final answer names a labelled file. The index is built once (692 files, 12 s) and copied into each run's fresh
home; `evals/context/` is removed from the snapshot so answers can't be looked up. One run per question per
config, configs interleaved per question; 138 runs, 4 h, 2 timeouts (600 s), no backend errors. The frontier
model half was not run (the user chose the local model only) and the live cache check needs Anthropic
(LM Studio reports no cached tokens: `cacheRead` 0 throughout).

Pass rule (fixed before running, spec §9): a variant beats its baseline if success rises, or total tokens fall
>= 10% without success falling. E1 baseline A (hint off), E5 baseline D (orientation off).

| Config | Hint | Orientation | Success | Total tokens | Median tokens | Median s | Tool calls |
|---|---|---|---|---|---|---|---|
| A | off | both | 21/23 | 1,047,250 | 30,426 | 94.8 | 119 |
| B | always | both | 21/23 | 919,791 | 33,597 | 90.5 | 110 |
| C | gated (10 / 1.0) | both | 23/23 | 1,175,157 | 25,460 | 74.0 | 129 |
| D | off | off | 22/23 | 1,205,568 | 39,032 | 95.3 | 138 |
| E | off | instructions | 21/23 | 980,903 | 34,144 | 84.3 | 125 |
| F | off | map | 21/23 | 1,185,106 | 35,675 | 87.5 | 135 |

Verdicts by the rule: E1 — B beats A (tokens −12%), C beats A (success). E5 — E, F and A do not beat D (each
one success lower, although A uses 13% and E 19% fewer total tokens; A beats D on per-question tokens in 15 of
23 questions).

Reading: success is at the ceiling (21–23/23) and the 9 failures cluster on two questions (the site-replay ADR
and the antlr/pom version conflict), so 1–2 success differences are within single-run noise; this set is too
easy to separate configs on success. The gated hint had the best success, median tokens and median time, but a
single 191k-token run makes its total the second highest. Orientation lowers typical token use without a
measurable success effect. Conclusion for defaults: keep orientation `both` and hint `gated`.

### Hint false fires (E1 negatives)

10 chit-chat / off-topic messages (`sophi-arena/context_qa/negatives.tsv`) against the same SoPhi index,
`sophi context eval --hint-grid` (the gate is deterministic over a fixed index, so this equals the live fire
rate). Top-1 scores: min 6.32, median 10.95, p90 20.45 — chit-chat reaches the score floor.

| Gate (minScore / minRatio) | Negatives fired | queries.tsv coverage / precision | commits.tsv coverage / precision |
|---|---|---|---|
| 10 / 1.0 (M2 default) | 6/10 | 1.000 / 0.839 | 0.981 / 0.735 |
| 10 / 1.1 | 3/10 | 0.839 / 0.846 | 0.733 / 0.767 |
| 12 / 1.1 | 1/10 | 0.839 / 0.846 | 0.725 / 0.775 |
| 15 / 1.0 | 1/10 | 0.968 / 0.833 | 0.868 / 0.768 |
| **10 / 1.25 (new default)** | **0/10** | 0.516 / 1.000 | 0.574 / 0.784 |

On the 23 live questions (SoPhi only) the gate at 10 / 1.25 fires on 12 and every fired hint names a right file
(at 10 / 1.0 it fires on 23 with precision 0.826).

Decision: `HintGate.DEFAULT_MIN_RATIO` 1.0 → 1.25, minScore stays 10. Rule for this change: no false fires on
the negatives, tightening the scale-free top1/top2 ratio rather than the score floor — BM25 scores grow with
corpus size (see the Limits above), so a higher floor (15 / 1.0 has more coverage here) would be tuned to
SoPhi's size. Cost: about half the real questions get no hint (they still have `search_context`). The live run
above used the old gate (10 / 1.0); the new default is calibrated offline, not re-run live.

## E4 compaction (small local model, 2026-10-15)

Setup: `sophi-cli` at a3637a7, model `prism-ml/bonsai-27b` on LM Studio, window 16,384, max tokens 4,096, runner
`sophi-arena/context_qa` (branch feat/context-qa, fa5e44e), run `runs/context-qa/20261015-213726`. 5 prompts, each
pairing two of the planned 10 repo questions: the 10 single questions finished in about 3 rounds and never
compacted, so the pairs make turns long enough to. One run per prompt per config, configs interleaved. Success =
the answer holds the labelled values (rescored, see below).

Pass rule (fixed before running, paired n=5): a variant beats G if success rises, or total tokens fall >= 10%
without success falling.

| Config | Compactor (`SOPHI_COMPACTION`) | Success | Total tokens | Median tokens | Summarise | Elide | Early stops |
|---|---|---|---|---|---|---|---|
| G | generic (today's compactor) | 3/5 | 157,549 | 21,320 | 1 | 0 | 2 (ContextExhausted, no answer) |
| L | elide | 5/5 | 177,031 | 32,065 | 0 | 3 | 0 |
| S | structured (default) | 5/5 | 253,809 | 32,341 | 0 | 3 | 0 |

Verdicts: L beats G (success 5/5 vs 3/5); S beats G (success 5/5 vs 3/5). G's totals are lower only because two
of its runs stopped after 1 to 2 calls.

Findings:
- bonsai issues 8-10 parallel reads in one round. At 16k that round alone overflowed the compaction trigger, and
  the old compactor, which could only cut whole older rounds, had to end the turn. This drove the elide modes'
  `Elider.elideLargest`: stub the largest results, the newest round included, down to 3/4 of the trigger. A result
  the model has not seen yet is stubbed as "too large to show at once; re-read a smaller part or narrow the request".
- The summariser never ran for L or S: elision alone always brought relief. So L and S executed the same path, and
  the token gap between them is one long S run (125,945 tokens in all, 15 calls), i.e. run variance, not a cost of the
  structured summary.
- Scoring fixes made during analysis: Kotlin literals (`3_000`, `50L`) and qualified names
  (`AnthropicCacheStrategy.SYSTEM_AND_TOOLS`) were wrongly rejected. 4 runs were rescored
  (`results.rescored.jsonl`), each verified by reading the answer.

Decision: the default stays `structured` (`SOPHI_COMPACTION`, no code change to defaults). E4 validated elision
and the oversized-round handling against today's compactor. It did not exercise the structured summary live: with
this model and these prompts the summariser was never needed. A longer-task run, where summaries must be
written, would settle structured against generic summaries.

Limits: the E4 prompts were single-turn, so only mid-turn compaction ran; the cross-turn path (USER/ASSISTANT summary pair, `_workingSet` merge) is covered by unit tests only. Single runs, one small local model (bonsai only), n=5 prompts, of which 2 never compacted in any config, so the decision rests on 3 informative prompts (2 decisive): effective n=3, and 2 successes of difference is a small
sample; no frontier model.

## E2 vocabulary gap (offline, 2026-10-17)

Corpus: SoPhi main checkout @ ab24bd4 and sophi-companion @ 679d278, indexed into a temp store per run. Sets: queries.tsv (31),
commits.tsv (258), docs.tsv (37, new). Variants: bm25 (today), +heuristic (`--summaries heuristic`), +llm (`--summaries llm`,
bonsai-27b, summaries for files in some query's lexical top 10, cached by content hash), +embeddings (`--embed text-embedding-nomic-embed-text-v1.5`, 768 dims, RRF k 60, 50 neighbours).
Model server: LAN machine 192.168.0.108:1234 (embeddings reached through a loopback TCP forward, the eval refuses non-loopback embed URLs).

Pass rule (fixed before running): a variant ships on if its MRR improves by >= 0.03 on commits.tsv or docs.tsv, queries.tsv MRR does not
drop by > 0.01, and p95 latency stays < 150 ms on every set.

Order of runs: bm25, heuristic, embeddings, llm per set, queries then commits then docs (one pass). The first-pass p50/p95 were taken while
bonsai was summarising on the same machine, so six variants whose first-pass p95 landed within 30% of 150 ms (105-195 ms) were re-run alone (R9b): bm25 queries (134.0),
bm25 commits (156.0), heuristic commits (153.5), embeddings queries (116.6), embeddings commits (129.4), and embeddings docs (103.3, near the edge of the band, re-run for symmetry with the other embeddings sets).
The re-run policy (re-run, show both) was decided after seeing the first runs, and no verdict depends on it: the verdicts are the same on first-pass numbers alone. The re-run outputs were not saved; those cells are terminal output, not saved.
The CLI prints no separate index time; "wall" is the whole run (JVM start, indexing both repos, summaries or embeddings, all queries).
Docs-set note: about 7 of the 37 docs.tsv queries share title words with their gold doc, so docs numbers flatter lexical retrieval somewhat.

| Variant | Set | hit@1 | hit@5 | hit@10 | MRR | p50 ms | p95 ms (run 1 / re-run) | wall |
|---|---|---|---|---|---|---|---|---|
| bm25 | queries (31) | 0.68 | 0.87 | 0.97 | 0.76 | 27.8 / 13.6 | 134.0 / 24.1 | 17 s |
| bm25 | commits (258) | 0.52 | 0.74 | 0.83 | 0.62 | 32.1 / 14.1 | 156.0 / 22.8 | 76 s |
| bm25 | docs (37) | 0.46 | 0.76 | 0.84 | 0.58 | 15.4 | 37.6 | 12 s |
| +heuristic | queries | 0.68 | 0.87 | 0.97 | 0.76 | 25.2 | 78.5 | 19 s |
| +heuristic | commits | 0.52 | 0.74 | 0.82 | 0.62 | 32.8 / 15.8 | 153.5 / 25.6 | 83 s |
| +heuristic | docs | 0.46 | 0.76 | 0.84 | 0.58 | 15.5 | 31.6 | 13 s |
| +llm | queries | 0.68 | 0.90 | 0.97 | 0.77 | 26.5 | 90.1 | 1180 s (192 files summarised) |
| +llm | commits | 0.51 | 0.75 | 0.83 | 0.62 | 15.6 | 26.1 | 1940 s (cache ~599 files by then, cumulative) |
| +llm | docs | 0.54 | 0.78 | 0.81 | 0.65 | 13.5 | 21.6 | 128 s (cache warm, a few new files) |
| +embeddings | queries | 0.55 / 0.58 | 0.90 / 0.94 | 0.90 / 0.94 | 0.70 / 0.74 | 62.6 / 52.9 | 116.6 / 65.9 | 85 s |
| +embeddings | commits | 0.49 / 0.49 | 0.73 / 0.72 | 0.79 / 0.79 | 0.60 / 0.59 | 69.2 / 59.3 | 129.4 / 76.4 | 163 s |
| +embeddings | docs | 0.46 / 0.46 | 0.89 / 0.89 | 0.97 / 0.97 | 0.65 / 0.64 | 62.7 / 60.9 | 103.3 / 74.4 | 82 s |

The llm variant's wall time is dominated by bonsai calls (about 10-13 files/min on the LAN server); total files summarised across the three sets: about 599 (cache line count).
Embedding runs are not exactly reproducible: the queries-set MRR was 0.70 and then 0.74 on re-run (the vector index logs a FALLBACK ("Built 0 active vector IDs from in-memory vectorIndex", and "Could not read vectors from pages (database closing), using existing vectorIndex with 0 entries")
on every embed run, so neighbours come from the fallback path). Either figure fails the rule below.

Deltas in MRR against bm25 (queries / commits / docs) and verdicts by the pass rule:

| Variant | dMRR queries | dMRR commits | dMRR docs | p95 < 150 on every set | Verdict |
|---|---|---|---|---|---|
| +heuristic | 0.00 | 0.00 | 0.00 | yes (re-run) | stays off (no set improves by 0.03) |
| +llm | +0.01 | 0.00 | +0.07 | yes | passes the rule (docs +0.07, queries not down); default stays off, production path not built |
| +embeddings | -0.06 (first) / -0.02 (re-run) | -0.02 / -0.03 | +0.07 / +0.06 | yes (re-run 76 ms) | inconclusive, stays off (fails the rule, but every run hit the vector-index fallback and runs differ) |

Examples (from the `miss:` lines, which also carry ranks: rN with N <= 10 is a hit at rank N, MISS means not in the top 10):
- heuristic: almost no movement. One commits query, "Feat/agent thinking indicator (#16)", went from hit to MISS (commits hit@10 .83 to .82); other changes only swapped which wrong file ranked first for already-missed queries ("Tool approval fix"). Queries and docs: identical hits.
- llm: docs: "why the build uses a parent POM rather than Gradle" improved in rank (r10 to better than 10); "how thumbs up and thumbs down feedback changes later behaviour" fell from rank 7 to MISS (hit@10 .84 to .81, hit@1 .46 to .54). Commits: "durability review follow-ups (#102)" went from MISS to a hit; "add stream error handling, tools TODO comments, and stream tests" newly MISS; "connect to configured MCP servers and register their tools" is a MISS in both bm25 and llm (only the wrong top hit changed). Queries: "summarise older conversation when the history gets too long" improved from r6 to a hit within the top 5 (llm hit@1 is unchanged).
- embeddings: docs MISS to hit: "how the main agent hands work to a specialised helper agent", "warning the user before a trip about something they always forget", "where the model interface lives so the core can call providers without Spring" (hit@10 .84 to .97). Commits MISS to hit: "Companion notification center (#51)" (verified); hit@10 still falls .83 to .79 overall. Queries: "how do I change the port the hub listens on" MISS to hit, but "a stopped turn is replayed as a note instead of the marker" and "base system prompt telling the model it is Sophi" newly MISS.

Baseline drift against M1 (queries 0.80, commits 0.64): bm25 now 0.76 and 0.62. The repos changed since M1 (new files, including the eval tsv files themselves); schema v2 does not change ranking.

Limits: one corpus; the eval .tsv files (queries/commits/docs.tsv) are in the index and appear as top hits for several queries (e.g. wrong-file hits for queries and commits), which depresses all variants; the llm variant only re-ranks files already in the lexical top 10 (by design, mirrors production), so it cannot raise hit@10 above bm25's reach; embedding results are for one model (nomic v1.5, 768 dims) and the fallback vector path noted above; first-pass latencies were contaminated by concurrent load, re-runs are the clean figures; the docs set has the title-overlap bias noted above, and the llm docs gain (+0.07 MRR) comes from 37 queries, so roughly 3 queries moved.

Decision: heuristic summaries stay off (no set moves). LLM summaries pass the pre-registered rule, but the case rests on docs alone (+0.07 MRR, about 3 of 37 queries; commits and queries gains are nil), the cost was about 55 minutes of bonsai wall time offline, and the production path (lazy summarising at query time) does not exist, so the default stays off and a follow-up plan builds that path. Embeddings are recorded as inconclusive and stay off: every run logged SEVERE `LSMVectorIndex FALLBACK: Built 0 active vector IDs`, and runs were not reproducible (queries MRR 0.70 vs 0.74). Follow-up: verify that ChunkVec neighbours do not come from a degraded path, then re-test. Two more findings: with vectors on there is no distance floor, so "No matches" never appears; and the eval `.tsv` files are indexed (follow-up: exclude `evals/` from indexing).

## E3 tool surface (small local model, 2026-10-17)

`prism-ml/bonsai-27b` on LM Studio, jar from `caaaa05` (schema 2), 23 FILES-answer questions x 3 configs, shipped hint/orientation defaults (`gated`, `both`), isolated home per run. Success = any gold path in the answer. Sweep: `sophi-arena/runs/context-qa/20261017-215957`.

config | tools | n | success | total tokens | tool calls | find_symbol+related calls | median first read | timed out
---|---|---|---|---|---|---|---|---
T | all (5) | 23 | 21 | 1003192 | 117 | 0 | 2 | 1
K | core (2) | 23 | 22 | 1084995 | 134 | 0 | 2 | 0
N | none | 23 | 20 | 1482558 | 149 | 0 | 2 | 0

Paired pass rule vs T (n=23, no void runs):
- K vs T: beats baseline (success 22 vs 21). The single extra success is a question where T timed out (600 s); tokens are +8%. Treat as a tie, not evidence that fewer tools help.
- N vs T: does not beat baseline (success 20 vs 21, tokens +48%). The context tools help: without them the model needs more grep/read calls.

Findings: bonsai never called `find_symbol` or `related` in any of 23 T runs (0 calls); it used only `search_context` (25) and `read_context` (6). K used `search_context` 34 times. No tool errors in any transcript, and N called no context tool. These questions are file-locating, so the graph tools had nothing to add; a symbol/relationship task set would be needed to test them.

Verdict: by the fixed rule K nominally beats `all` on this model (a tie in substance: one T timeout, +8% tokens). `core` is fine for a small local model, but the default stays `all`: there is no tier detection, frontier models are untested, and `find_symbol`/`related` were never called, so this set cannot judge them. `none` loses. Follow-up: a symbol/relationship question set, and a frontier-tier run.

## Still pending

Frontier-model E1/E5, a longer-task E4 that exercises the summary, and the live Anthropic cache check (procedure: `evals/context/EXPERIMENTS.md`); a harder
task set if success differences matter. Also pending: the LLM-summary production path (lazy, at query time; ADR-041 §10) and an embeddings re-test after verifying
that the ChunkVec neighbours do not come from the `LSMVectorIndex FALLBACK` path.
