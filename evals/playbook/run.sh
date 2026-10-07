#!/usr/bin/env bash
# One isolated Sophi run of a playbook case: a fresh user.home (seeded from home-seed/), a fresh copy
# of the ledger fixture as cwd, and the case's turns piped to stdin (one line = one turn, EOF ends
# the session). Usage: evals/playbook/run.sh <case-id> [run-number]
# Env: SOPHI_JAR, PLAYBOOK_RUN_TIMEOUT (seconds per run, default 1200), PLAYBOOK_RUNS (run dirs; default ~/Library/Caches/sophi-playbook), SOPHI_FLAGS (default = the companion's "Remote Local" profile + --god-mode --no-remote), AB_LABEL.
set -o pipefail
export PB="$(cd "$(dirname "$0")" && pwd)"
source "$PB/cases.sh" || exit 2
id="$1"; n="${2:-1}"; fn="${id//-/_}"
declare -F "turns_$fn" >/dev/null || { echo "unknown case: $id" >&2; exit 2; }
JAR="${SOPHI_JAR:-$PB/../../sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar}"
[ -f "$JAR" ] || { echo "no jar at $JAR — build it: mvn -q -pl sophi-cli -am package -DskipTests" >&2; exit 2; }
RESULTS="${RESULTS:-$PB/runs/results.tsv}"
DEFAULT_FLAGS="--provider openai-compat --base-url http://192.168.0.108:1234/v1 --model prism-ml/bonsai-27b --context-window-tokens 32768 --max-tokens 16384 --llm-timeout-seconds 300 --memory --embedding-model text-embedding-nomic-embed-text-v1.5 --embedding-dimensions 768 --god-mode --no-remote"
read -ra FLAGS <<< "${SOPHI_FLAGS:-$DEFAULT_FLAGS}"

# Outside $TMPDIR (macOS purges it after ~3 days) so scorecards' RUN paths stay inspectable.
RUNS_ROOT="${PLAYBOOK_RUNS:-$HOME/Library/Caches/sophi-playbook}"; RUNS_ROOT="${RUNS_ROOT%/}"
mkdir -p "$RUNS_ROOT" && RUN="$(mktemp -d "$RUNS_ROOT/sophi-pb-$id-$n.XXXX")" || exit 1
mkdir -p "$RUN/home/.sophi" "$RUN/work"
cp -R "$PB/home-seed/." "$RUN/home/.sophi/"
cp -R "$PB/fixtures/ledger/." "$RUN/work/"
cd "$RUN/work" || exit 1
"setup_$fn"; rc=$?
[ $rc -eq 0 ] || { echo "setup failed (rc=$rc) for $id" >&2; exit 1; }

# Only the calendar case may reach Calendar.app; every other run gets the osascript guard.
case " ${CALENDAR_CASES[*]} " in *" $id "*) SPATH="$PATH" ;; *) SPATH="$PB/guardbin:$PATH" ;; esac
session() { "$1" | PATH="$SPATH" java -Duser.home="$RUN/home" -jar "$JAR" "${FLAGS[@]}" 2>&1 | tee -a "$RUN/transcript.txt"; }
# Wall-clock cap per run (both sessions together). Past it, this run's Sophi is killed and the run
# scores fail: seen live, coding-4 worked alone for 55 min and then the model stream died.
CAP="${PLAYBOOK_RUN_TIMEOUT:-1200}"
# Kills the whole tree (bash commands, MCP servers, a delegated claude) or a child keeps the pipe open.
killtree() { local c; for c in $(pgrep -P "$1"); do killtree "$c"; done; kill "$1" 2>/dev/null; }
( sleep "$CAP"; touch "$RUN/.timed-out"; for p in $(pgrep -f -- "-Duser.home=$RUN/home"); do killtree "$p"; done ) & WATCHDOG=$!
session "turns_$fn"
declare -F "turns_${fn}_2" >/dev/null && [ ! -e "$RUN/.timed-out" ] && session "turns_${fn}_2"
pkill -P $WATCHDOG 2>/dev/null; kill $WATCHDOG 2>/dev/null

if "check_$fn" >/dev/null 2>&1; then result=pass; else result=fail; fi
declare -F "teardown_$fn" >/dev/null && "teardown_$fn"
S="$RUN/home/.sophi/sessions"
calls="$(cat "$S"/*.jsonl 2>/dev/null | grep -F '\"name\":\"invoke_claude_code\"')"
denials=$(cat "$S"/*.jsonl 2>/dev/null | grep -cF "Tool 'invoke_claude_code' execution denied")
delegated=no; [ "$(grep -c . <<< "$calls")" -gt "$denials" ] && delegated=yes
is_delegate=no; case " ${DELEGATE_CASES[*]} " in *" $id "*) is_delegate=yes ;; esac
case " ${DELEGATION_REQUIRED[*]} " in *" $id "*) [ $delegated = no ] && result=fail ;; esac

# A void run is not evidence about Sophi: it's excluded from pass counts (README A/B) and re-run.
# Delegate cases script one 'y' for the invoke_claude_code prompt (it is always HIGH_RISK); any
# other [y/N] prompt swallowed a scripted turn.
prompts=$(grep -c '\[y/N\]' "$RUN/transcript.txt")
allowed=0; [ $is_delegate = yes ] && allowed=$(grep -cE "wants to run 'invoke_claude_code'|- invoke_claude_code \(" "$RUN/transcript.txt")
void=""
[ "$prompts" -gt "$allowed" ] && void="a [y/N] prompt consumed a scripted turn — later turns are shifted"
grep -qs 'evals/playbook' "$S"/*.jsonl && void="the session touched evals/playbook — it may have seen the answers"
grep -q '\[error: ' "$RUN/transcript.txt" && void="the model backend errored mid-run"
case " ${FLAGS[*]} " in *" --memory "*) grep -q '^memory: disabled' "$RUN/transcript.txt" && \
  void="memory was requested but switched itself off (embeddings endpoint) — memory cases would pass by other means" ;; esac
[ -n "$calls" ] && grep -vqF "$(basename "$RUN")" <<< "$calls" && \
  void="invoke_claude_code was pointed OUTSIDE the run dir — check that project for changes Claude Code made"
[ -n "$void" ] && { echo "VOID: $void"; result=void; }
[ -e "$RUN/.timed-out" ] && { echo "TIMEOUT: run exceeded ${CAP}s, scored fail (too slow is a result, not a void)"; result=fail; }
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%F)" "$id" "$n" "${AB_LABEL:-baseline}" "$result" "$delegated" "$RUN" >> "$RESULTS"
echo "case=$id run=$n result=$result delegated=$delegated"
echo "RUN=$RUN  (transcript.txt, home/.sophi/sessions/*.jsonl, home/.sophi/learning/)"
