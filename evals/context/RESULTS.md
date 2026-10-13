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
