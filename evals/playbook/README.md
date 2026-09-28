# Claude × Sophi use-case playbook

Measures the local Sophi (`prism-ml/bonsai-27b` on LM Studio) on 16 fixed cases, with Claude as the critic, and keeps
only the harness fixes that measurably help. Spec:
`docs/superpowers/specs/2026-09-26-claude-sophi-usecase-playbook-design.md` (local, gitignored).

## One-time setup

- `sophi-cli` jar built: `mvn -q -pl sophi-cli -am package -DskipTests`
- LM Studio at `192.168.0.103:1234` serving `prism-ml/bonsai-27b` and `text-embedding-qwen3-embedding-0.6b` (override with `SOPHI_FLAGS`)
- a Calendar.app calendar named `Sophi Eval` (the first run asks for automation permission)
- `BRAVE_SEARCH_API_KEY` exported (else `assistant-4` is skipped)
- the `claude` CLI logged in (delegate cases)

## Loop

1. **Selftest** (no LLM, after any edit to `cases.sh`): `evals/playbook/selftest.sh`
2. **Run:** `evals/playbook/run.sh <case> [n]`. Each run is isolated in its own temp `user.home`;
   the real `~/.sophi` is never touched. The result is appended to `runs/results.tsv`.
3. **Score:** ask Claude Code:
   > Score playbook run: case `<id>`, run `<n>`, RUN=`<path>`. Read `evals/playbook/rubric.md`,
   > the case in `cases.sh` and `catalogue.md`, then `$RUN/transcript.txt` and
   > `$RUN/home/.sophi/sessions/*.jsonl`. Write `evals/playbook/runs/<date>-<id>-<n>.md`.
4. **`unclear` → Claude-backed re-run:**
   `SOPHI_FLAGS="--provider claude --model claude-sonnet-5 --memory --embedding-base-url http://192.168.0.103:1234/v1 --embedding-model text-embedding-qwen3-embedding-0.6b --embedding-dimensions 1024 --god-mode --no-remote" AB_LABEL=claude evals/playbook/run.sh <case>`

## A/B a prescription

Run the failing case and the other 3 in its area, 3 times each, before and after the change:

    for c in tool-1 tool-2 tool-3 tool-4; do for n in 1 2 3; do AB_LABEL=before evals/playbook/run.sh $c $n; done; done
    # apply the change: a file in home-seed/, or a rebuilt jar via SOPHI_JAR
    for c in tool-1 tool-2 tool-3 tool-4; do for n in 1 2 3; do AB_LABEL=after evals/playbook/run.sh $c $n; done; done
    awk -F'\t' '$4=="before"||$4=="after" {t[$2" "$4]++; if ($5=="pass") p[$2" "$4]++} END {for (k in t) print k, p[k]+0 "/" t[k]}' evals/playbook/runs/results.tsv | sort

Keep the change only if the failing case's pass count went up and no other case's went down.
Kept `home-seed/` files are promoted into the real `~/.sophi` by hand; kept prompt or
tool-description changes become ordinary PRs.
