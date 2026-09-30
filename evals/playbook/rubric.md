# Rubric

Claude fills one scorecard per run at `runs/YYYY-MM-DD-<case>-<n>.md`.

## Rules

1. **No evidence, no fault tag.** Quote the exact JSONL line (or transcript line) that shows the
   failure.
2. **Exactly one fault tag and one prescription** per non-passing run.
3. **Delegation.** `delegate-1` asks for delegation explicitly: it passes only if `invoke_claude_code`
   was called, not denied, and the end state is correct. plan-4 and coding-4 are **outcome cases**:
   a correct end state passes however it was reached (solo is fine), and delegation is recorded in
   `results.tsv`. A plan-4/coding-4 *fail* where Sophi never tried to delegate gets
   `fault: missed-delegation`.
4. **`result=void` runs are not scored.** `run.sh` voids a run when a `[y/N]` prompt swallowed a
   scripted turn, the session touched `evals/playbook`, the model backend errored, or
   `invoke_claude_code` was pointed outside the run dir (check that project for changes!). Fix the
   cause and re-run; void rows are excluded from A/B counts.
5. **`unclear`** is the only tag that triggers a Claude-backed re-run (command in `README.md`).
   Claude passes → retag `model-capacity`. Claude fails → retag with the harness fault it shows.

## Fault tags

| tag | prescription |
|---|---|
| `prompt` | a diff to the system prompt |
| `tool-description` | a diff to that tool's description or parameter schema |
| `missing-knowledge` | a lesson (`kind` ∈ tool_usage, environment, approach, user_context, preference + `text`) or a skill edit, placed in `home-seed/` |
| `harness-bug` | an issue describing the code defect; not fixed inside the run |
| `model-capacity` | none; mark the run as a Phase B candidate |
| `missed-delegation` | a lesson or prompt change about when to hand work to Claude Code (outcome cases only) |
| `case-bug` | fix the case in `cases.sh`; discard the run |
| `unclear` | Claude-backed re-run, then retag |

## Scorecard template

    # <case> run <n> — <date>

    - RUN: <path>
    - outcome: pass | partial | fail
    - tool use (0–2): <n> — <one line why>
    - planning (0–2): <n> — <one line why>
    - efficiency: <tool calls made> vs <estimated minimum>
    - delegation: correct | missed | unnecessary | n/a
    - fault: <tag>            (omit on pass)
    - evidence: <quoted line> (omit on pass)
    - prescription: <the one fix, concrete enough to apply as-is> (omit on pass)
