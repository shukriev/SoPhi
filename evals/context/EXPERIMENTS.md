# Context engine — live experiment procedure (E1 hint, E5 orientation)

Status: **small local model done** (bonsai-27b: E1/E5 on 2026-10-14, E4 on 2026-10-15, plus E2 offline and E3 below; results in `RESULTS.md` → "Live E1/E5", "E4 compaction"); the frontier-model runs and the live Anthropic cache check are still pending. Runner: `sophi-arena/context_qa`. Every run spends model tokens, so each needs the user's go-ahead first.

## Knobs

Environment variables, read once when the runtime is built; internal, not user settings. Unknown values fall back to the default (case-insensitive).

| Variable | Values (default first) |
|---|---|
| `SOPHI_CONTEXT_HINT` | `gated`, `always`, `off` |
| `SOPHI_CONTEXT_ORIENTATION` | `both`, `instructions`, `map`, `off` |
| `SOPHI_COMPACTION` | `structured`, `elide`, `generic` |
| `SOPHI_CONTEXT_TOOLS` | `all`, `core` (search_context, read_context), `none`; E3: keep `all` (core is fine for small local models, a tie in substance) |
| `SOPHI_CONTEXT_EMBED` / `_URL` / `_DIMS` | embedding model name (unset or blank = off), loopback base URL, vector dimensions; experiment-only, see below |
| `SOPHI_USAGE_LOG` | path of a JSONL usage log (unset = none); one line per `turn` / `summarise` / `elide` call: input tokens (all prompt tokens, including cache read/write), `cacheRead`, output, model, wall-clock `ts` (latency only from `ts` deltas) |

## Runs

Run each variant on the same task set, in a registered project, with a fresh `HOME` per run so memory and learning do not leak between variants. One small local model and one frontier model per experiment.

- **E1 — hint:** `SOPHI_CONTEXT_HINT` = `off` / `always` / `gated`, orientation at its default.
- **E5 — orientation:** `SOPHI_CONTEXT_ORIENTATION` = `off` / `instructions` / `map` / `both`, hint `off` so the effect is isolated.
- **E4 — compaction:** `SOPHI_COMPACTION` = `generic` / `elide` / `structured`, runner `sophi-arena/context_qa` set `compaction` with a small window (16k) so turns compact. Status: **done on bonsai-27b (2026-10-15)**, results in `RESULTS.md` -> "E4 compaction"; the structured summary path was not exercised (the summariser never ran for `elide`/`structured`), so a longer-task run is still open; no frontier-model run.
- **E2 — vocabulary gap (offline):** `sophi context eval --summaries heuristic|llm`, `--embed <model>`; done 2026-10-17, results in `RESULTS.md` -> "E2 vocabulary gap". Heuristic: off. LLM summaries: pass the rule on docs but the production path is unbuilt, so off. Embeddings: inconclusive (vector-index FALLBACK on every run, not reproducible), off. To try embeddings: run a local OpenAI-compatible embedding server, set `SOPHI_CONTEXT_EMBED=<model>` (plus `_URL`, loopback only, and `_DIMS`) before the host starts; a Finder-launched companion does not see shell variables, so use `launchctl setenv SOPHI_CONTEXT_EMBED <model>` (or start the companion from a terminal).
- **E3 — tool surface:** `SOPHI_CONTEXT_TOOLS` = `all` / `core` / `none` on bonsai-27b. Status: **done on bonsai-27b (2026-10-17)**, results in `RESULTS.md` -> "E3 tool surface"; `core` nominally beats `all` (tie in substance), the default stays `all`; `find_symbol`/`related` were never called, so a symbol/relationship question set and a frontier run are pending.
- **Live cache check (E5):** two consecutive turns on Anthropic with `SOPHI_USAGE_LOG` set; turn 2 `cacheRead` should be > 0 (system prompt above the model's minimum cacheable prefix, e.g. 1,024 tokens for Sonnet). On LM Studio `cacheRead` may stay 0 if the server does not report it; record input tokens and timings instead. The structural guarantee (byte-identical system prompts across turns) is already covered by `RuntimeBuilderContextTest`. Also compare `cacheRead` from one non-streaming and one streaming call on the same prompt and record whether they agree (streaming takes the cache figures from `message_delta`, which some models or API versions may omit).

## Metrics per run

Success (task done / not), total tokens, tool calls, rounds to the first relevant read, `cacheRead` tokens, per-turn latency.

## Pass rule

A variant beats the baseline if it improves success, or cuts total tokens by >= 10% without lowering success.

## E1 must include negatives

The offline calibration had no negative messages (chit-chat, off-topic), so false fires are unmeasured. The live E1 task set must include such messages and record how often the hint fires on them.

## Open design point: corpus-relative threshold

BM25 scores grow with corpus size, so the calibrated absolute floor (top score >= 10) does not fire on tiny repos (see "Hint gate results" -> Limits in `RESULTS.md`). If the live E1 shows the hint helps, evaluate a corpus-relative threshold (e.g. top score as a fraction of the query's attainable maximum, or a percentile of the index's own score distribution) before shipping.
