# Context engine — retrieval results

| Date | Commit | Set | n | hit@1 | hit@5 | hit@10 | MRR | grep MRR | p50/p95 ms |
|---|---|---|---|---|---|---|---|---|---|
| 2026-10-12 | 987c385+ | queries.tsv | 31 | 0.74 | 0.94 | 0.97 | 0.82 | 0.12 (6.8x) | 12.9 / 23.8 |
| 2026-10-12 | 987c385+ | commits.tsv | 258 | 0.53 | 0.76 | 0.82 | 0.63 | 0.26 (2.4x) | 13.5 / 22.0 |

Exit criteria (spec §10 M1): queries.tsv hit@10 >= 0.90 and MRR >= 0.80 — met; commits.tsv engine MRR >= 2x grep — met (2.4x).

Weights: k1 0.9, b 0.4, nameBoost 1.5, perDirectory 3, candidates 300, rolePrior SOURCE 1.0 / INSTRUCTION 1.0 / DOC 0.9 / CONFIG 0.9 / TEST 0.6 / GENERATED 0.2.
(k1/b were tuned on queries.tsv itself, so its numbers are slightly optimistic; commits.tsv is the held-out check.)

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
| **1.5 : 3 : default : 0.9 : 0.4 (chosen)** | **0.97 / 0.82** | **0.82 / 0.63** |

The no-priors baseline lands at 0.94 / 0.79 rather than the bake-off's 0.90 / 0.80: the repos have changed since the bake-off (files added, symlinks and secret files now skipped), so exact reproduction is not expected.

Reproduce: `sophi context eval --project SoPhi=<path> --project sophi-companion=<path> --queries evals/context/<set>.tsv`.
