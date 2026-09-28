#!/usr/bin/env bash
# One isolated Sophi run of a playbook case: a fresh user.home (seeded from home-seed/), a fresh copy
# of the ledger fixture as cwd, and the case's turns piped to stdin (one line = one turn, EOF ends
# the session). Usage: evals/playbook/run.sh <case-id> [run-number]
# Env: SOPHI_JAR, SOPHI_FLAGS (default = the companion's "Remote Local" profile + --god-mode --no-remote), AB_LABEL.
set -o pipefail
export PB="$(cd "$(dirname "$0")" && pwd)"
source "$PB/cases.sh" || exit 2
id="$1"; n="${2:-1}"; fn="${id//-/_}"
declare -F "turns_$fn" >/dev/null || { echo "unknown case: $id" >&2; exit 2; }
JAR="${SOPHI_JAR:-$PB/../../sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar}"
DEFAULT_FLAGS="--provider openai-compat --base-url http://192.168.0.103:1234/v1 --model prism-ml/bonsai-27b --context-window-tokens 32768 --max-tokens 16384 --llm-timeout-seconds 300 --memory --embedding-model text-embedding-qwen3-embedding-0.6b --embedding-dimensions 1024 --god-mode --no-remote"
read -ra FLAGS <<< "${SOPHI_FLAGS:-$DEFAULT_FLAGS}"

RUN="$(mktemp -d "${TMPDIR:-/tmp}/sophi-pb-$id-$n.XXXX")"
mkdir -p "$RUN/home/.sophi" "$RUN/work"
cp -R "$PB/home-seed/." "$RUN/home/.sophi/"
cp -R "$PB/fixtures/ledger/." "$RUN/work/"
cd "$RUN/work" || exit 1
"setup_$fn"; rc=$?
[ $rc -eq 0 ] || { echo "setup failed (rc=$rc) for $id" >&2; exit 1; }

session() { "$1" | java -Duser.home="$RUN/home" -jar "$JAR" "${FLAGS[@]}" 2>&1 | tee -a "$RUN/transcript.txt"; }
session "turns_$fn"
declare -F "turns_${fn}_2" >/dev/null && session "turns_${fn}_2"

if "check_$fn" >/dev/null 2>&1; then result=pass; else result=fail; fi
delegated=no
grep -qF '\"name\":\"invoke_claude_code\"' "$RUN"/home/.sophi/sessions/*.jsonl 2>/dev/null && delegated=yes
case " ${DELEGATE_CASES[*]} " in *" $id "*) [ "$delegated" = yes ] || result=fail ;; esac
grep -q '\[y/N\]' "$RUN/transcript.txt" && echo "WARNING: a [y/N] prompt consumed a scripted turn — later turns are shifted"
grep -qs 'evals/playbook' "$RUN"/home/.sophi/sessions/*.jsonl && echo "WARNING: the session touched evals/playbook — the run may have seen answers; score it as case-bug"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%F)" "$id" "$n" "${AB_LABEL:-baseline}" "$result" "$delegated" "$RUN" >> "$PB/runs/results.tsv"
echo "case=$id run=$n result=$result delegated=$delegated"
echo "RUN=$RUN  (transcript.txt, home/.sophi/sessions/*.jsonl, home/.sophi/learning/)"
