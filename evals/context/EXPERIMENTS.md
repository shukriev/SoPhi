# Context engine — live experiment procedure (E1 hint, E5 orientation)

Status: **pending**. The offline numbers are in `RESULTS.md`. The live runs, and the live prompt-cache check, were deferred to the end of the work (no provider was reachable when M2 landed). Nothing below has been run. Every run spends model tokens, so each needs the user's go-ahead first.

## Knobs

Environment variables, read once when the runtime is built; internal, not user settings. Unknown values fall back to the default (case-insensitive).

| Variable | Values (default first) |
|---|---|
| `SOPHI_CONTEXT_HINT` | `gated`, `always`, `off` |
| `SOPHI_CONTEXT_ORIENTATION` | `both`, `instructions`, `map`, `off` |
| `SOPHI_USAGE_LOG` | path of a JSONL usage log (unset = none); one line per `turn` / `summarise` call: input tokens (all prompt tokens, including cache read/write), `cacheRead`, output, timing |

## Runs

Run each variant on the same task set, in a registered project, with a fresh `HOME` per run so memory and learning do not leak between variants. One small local model and one frontier model per experiment.

- **E1 — hint:** `SOPHI_CONTEXT_HINT` = `off` / `always` / `gated`, orientation at its default.
- **E5 — orientation:** `SOPHI_CONTEXT_ORIENTATION` = `off` / `instructions` / `map` / `both`, hint `off` so the effect is isolated.
- **Live cache check (E5):** two consecutive turns on Anthropic with `SOPHI_USAGE_LOG` set; turn 2 `cacheRead` should be > 0 (system prompt >= 1,024 tokens). On LM Studio `cacheRead` may stay 0 if the server does not report it; record input tokens and timings instead. The structural guarantee (byte-identical system prompts across turns) is already covered by `RuntimeBuilderContextTest`.

## Metrics per run

Success (task done / not), total tokens, tool calls, rounds to the first relevant read, `cacheRead` tokens, per-turn latency.

## Pass rule

A variant beats the baseline if it improves success, or cuts total tokens by >= 10% without lowering success.

## E1 must include negatives

The offline calibration had no negative messages (chit-chat, off-topic), so false fires are unmeasured. The live E1 task set must include such messages and record how often the hint fires on them.

## Open design point: corpus-relative threshold

BM25 scores grow with corpus size, so the calibrated absolute floor (top score >= 10) does not fire on tiny repos (see "Hint gate results" -> Limits in `RESULTS.md`). If the live E1 shows the hint helps, evaluate a corpus-relative threshold (e.g. top score as a fraction of the query's attainable maximum, or a percentile of the index's own score distribution) before shipping.
