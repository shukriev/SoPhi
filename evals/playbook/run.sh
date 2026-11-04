#!/usr/bin/env bash
# One isolated Sophi run of a playbook case: a fresh user.home (seeded from home-seed/), a fresh copy
# of the ledger fixture as cwd, and the case's turns sent as RPC commands (one line = one turn; /goal lines are goals) to
# `sophi --mode rpc`. Usage: evals/playbook/run.sh <case-id> [run-number]
# Env: SOPHI_JAR, PLAYBOOK_RUN_TIMEOUT (seconds per run, default 1200), PLAYBOOK_RUNS (run dirs; default ~/Library/Caches/sophi-playbook), LMSTUDIO_URL (default http://192.168.0.108:1234/v1), PLAYBOOK_MODEL (default qwen/qwen3-8b), SOPHI_FLAGS (default = --provider local + --god-mode --no-remote), AB_LABEL.
set -o pipefail
export PB="$(cd "$(dirname "$0")" && pwd)"
source "$PB/cases.sh" || exit 2
id="$1"; n="${2:-1}"; fn="${id//-/_}"
declare -F "turns_$fn" >/dev/null || { echo "unknown case: $id" >&2; exit 2; }
JAR="${SOPHI_JAR:-$PB/../../sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar}"
[ -f "$JAR" ] || { echo "no jar at $JAR — build it: mvn -q -pl sophi-cli -am package -DskipTests" >&2; exit 2; }
RESULTS="${RESULTS:-$PB/runs/results.tsv}"
LMSTUDIO_URL="${LMSTUDIO_URL:-http://192.168.0.108:1234/v1}"
DEFAULT_FLAGS="--provider local --base-url $LMSTUDIO_URL --model ${PLAYBOOK_MODEL:-qwen/qwen3-8b} --max-tokens 16384 --llm-timeout-seconds 300 --memory --god-mode --no-remote"
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
is_delegate=no; case " ${DELEGATE_CASES[*]} " in *" $id "*) is_delegate=yes ;; esac
trap '' PIPE  # a killed Sophi must not kill this script on the next write
# Sends one command, then reads events (into events.jsonl) until the turn ends or the command is refused
# with an `error` (logged; no turn_ended follows). Returns 1 if Sophi is gone.
# Confirmations: only invoke_claude_code in a delegate case is approved; anything else is denied.
await_turn() {
  local ev ty c t ok
  while IFS= read -r ev <&4; do
    printf '%s\n' "$ev" >> "$RUN/events.jsonl"
    ty="$(jq -r '.type' <<< "$ev")"
    case "$ty" in
      turn_ended|error) return 0 ;;
      exited) return 1 ;;
      confirmation_requested)
        while IFS=$'\t' read -r c t; do
          ok=false; [ "$t" = invoke_claude_code ] && [ $is_delegate = yes ] && ok=true
          [ $ok = true ] || echo "rpc-client: denied $t" >> "$RUN/stderr.txt"
          jq -nc --arg c "$c" --argjson ok $ok '{type:"confirm",callId:$c,approved:$ok}' >&3
        done < <(jq -r '.requests[] | "\(.callId)\t\(.toolName)"' <<< "$ev") ;;
    esac
  done
  return 1
}
# A turn line becomes a prompt, or a goal for '/goal [--check "<cmd>"] <task>' (same split as GoalArgs).
turn_cmd() {
  local l="$1" rest chk="" q='^--check[[:space:]]+"([^"]*)"[[:space:]]*(.*)$' u='^--check[[:space:]]+([^[:space:]]+)[[:space:]]+(.*)$'
  if [ "${l#/goal }" = "$l" ]; then jq -nc --arg t "$l" '{type:"prompt",text:$t}'; return; fi
  rest="${l#/goal }"
  if [[ $rest =~ $q ]] || [[ $rest =~ $u ]]; then chk="${BASH_REMATCH[1]}"; rest="${BASH_REMATCH[2]}"; fi
  jq -nc --arg t "$rest" --arg c "$chk" '{type:"goal",text:$t} + (if $c == "" then {} else {check:$c} end)'
}
# One Sophi process over a pair of FIFOs (bash 3.2 on macOS has no coproc). Reading events while a turn
# runs is what keeps the client from deadlocking on a full pipe.
session() {
  local io pid line
  io="$(mktemp -d)"; mkfifo "$io/in" "$io/out"
  PATH="$SPATH" java -Duser.home="$RUN/home" -jar "$JAR" --mode rpc "${FLAGS[@]}" < "$io/in" > "$io/out" 2>> "$RUN/stderr.txt" & pid=$!
  exec 3> "$io/in" 4< "$io/out"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    turn_cmd "$line" >&3 && await_turn || break
  done < <("$1")
  exec 3>&-                                                  # stdin EOF: Sophi finishes and exits
  while IFS= read -r line <&4; do printf '%s\n' "$line" >> "$RUN/events.jsonl"; done
  exec 4<&-; wait $pid; rm -rf "$io"
}
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
case " ${DELEGATION_REQUIRED[*]} " in *" $id "*) [ $delegated = no ] && result=fail ;; esac

# A void run is not evidence about Sophi: it's excluded from pass counts (README A/B) and re-run.
void=""
jq -es 'any(.[]; .type == "ready")' "$RUN/events.jsonl" >/dev/null 2>&1 || void="Sophi did not start (see stderr.txt)"
grep -qs 'evals/playbook' "$S"/*.jsonl && void="the session touched evals/playbook — it may have seen the answers"
jq -es 'any(.[]; .type == "turn_ended" and .stopReason == "Error")' "$RUN/events.jsonl" >/dev/null 2>&1 && void="the model backend errored mid-run"
case " ${FLAGS[*]} " in *" --memory "*) jq -es 'any(.[]; .type == "ready" and .memory.enabled == false)' "$RUN/events.jsonl" >/dev/null 2>&1 && \
  void="memory was requested but switched itself off (embeddings endpoint) — memory cases would pass by other means" ;; esac
[ -n "$calls" ] && grep -vqF "$(basename "$RUN")" <<< "$calls" && \
  void="invoke_claude_code was pointed OUTSIDE the run dir — check that project for changes Claude Code made"
[ -n "$void" ] && { echo "VOID: $void"; result=void; }
[ -e "$RUN/.timed-out" ] && { echo "TIMEOUT: run exceeded ${CAP}s, scored fail (too slow is a result, not a void)"; result=fail; }
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%F)" "$id" "$n" "${AB_LABEL:-baseline}" "$result" "$delegated" "$RUN" >> "$RESULTS"
echo "case=$id run=$n result=$result delegated=$delegated"
echo "RUN=$RUN  (events.jsonl, stderr.txt, home/.sophi/sessions/*.jsonl, home/.sophi/learning/)"
