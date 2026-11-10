#!/usr/bin/env bash
# Tests run.sh's result logic against a fake Sophi (fakebin/java): no model, no network.
# Usage: evals/playbook/harness-test.sh
set -o pipefail
PB="$(cd "$(dirname "$0")" && pwd)"
export PATH="$PB/fakebin:$PATH" SOPHI_JAR="$PB/fakebin/java" RESULTS="$(mktemp)" PB_RUNS_ROOT="$(mktemp -d)"
export PLAYBOOK_RUNS="$PB_RUNS_ROOT"
fails=0
expect() {  # expect <label> <case> <FAKE_DO> <expected "result=… delegated=…">
  local got; got="$(FAKE_DO="$3" "$PB/run.sh" "$2" 1 2>&1 | sed -n 's/^case=[^ ]* run=[^ ]* //p')"
  if [ "$got" = "$4" ]; then echo "ok   $1"; else echo "FAIL $1: got '$got', want '$4'"; fails=$((fails+1)); fi
}
expect "plain pass"                 tool-1 "answer"                                    "result=pass delegated=no"
expect "stray confirmation is denied, not void" tool-1 "answer ask:bash"              "result=pass delegated=no"
expect "Sophi not starting voids"     tool-1 "nostart"                                   "result=void delegated=no"
expect "answer leak voids"          tool-1 "answer leak"                               "result=void delegated=no"
expect "backend error voids"        tool-1 "answer llmerror"                           "result=void delegated=no"
expect "memory switched off voids"  tool-1 "answer memoff"                            "result=void delegated=no"
expect "calendar blocked outside assistant-1" tool-1 "cal"                         "result=fail delegated=no"
expect "delegated + solved passes"  plan-4 "ask:invoke_claude_code call solve:plan_4"  "result=pass delegated=yes"
expect "outcome case: solo solve passes" plan-4 "solve:plan_4"                        "result=pass delegated=no"
expect "delegate-1: delegated + solved passes" delegate-1 "ask:invoke_claude_code call solve:delegate_1" "result=pass delegated=yes"
expect "delegate-1: solved alone fails"  delegate-1 "solve:delegate_1"                             "result=fail delegated=no"
expect "delegate-1: denied delegation fails" delegate-1 "ask:invoke_claude_code call denied solve:delegate_1" "result=fail delegated=no"
expect "extra confirmation is denied, not void" plan-4 "ask:invoke_claude_code call ask:bash solve:plan_4" "result=pass delegated=yes"
expect "goal turn is sent as a goal command" plan-4 "goalsolve:plan_4"                  "result=pass delegated=no"
expect "delegating elsewhere voids" plan-4 "ask:invoke_claude_code call-elsewhere solve:plan_4" "result=void delegated=yes"
for c in plan-4 coding-4 delegate-1; do  # delegate turns need no scripted 'y': the client answers confirmations
  t="$(cd "$(mktemp -d)" && source "$PB/cases.sh" && "turns_${c//-/_}")"
  if [ "$(tail -1 <<< "$t")" != y ] && grep -q "Work only inside /" <<< "$t"; then echo "ok   $c has no scripted y + work-dir scope"; else echo "FAIL $c turns: $t"; fails=$((fails+1)); fi
done
# .cal-before holds the user's real events: it must live outside the work dir Sophi can read.
d="$(mktemp -d)"; mkdir "$d/work"
if ( cd "$d/work" && source "$PB/cases.sh" && echo 'Home;Dentist;15:0;60' > ../.cal-before \
     && ! printf 'Home;Dentist;15:0;60\n' | new_real_dentist ); then echo "ok   .cal-before read from outside the work dir"
else echo "FAIL .cal-before is not read from ../"; fails=$((fails+1)); fi
# checks accept reasonable variants of a correct answer
tol() {  # tol <label> <case-fn> <shell that produces the variant>
  if ( cd "$(mktemp -d)" && cp -R "$PB/fixtures/ledger/." . && source "$PB/cases.sh" && eval "$3" && "check_$2" ); then echo "ok   $1"
  else echo "FAIL $1"; fails=$((fails+1)); fi
}
tol "plan-1 accepts 'retry_limit: 7'" plan_1 "mkdir config && printf '[network]\nretry_limit: 7  \n' > config/settings.ini && echo 'retry_limit configured' >> CHANGELOG.md"
tol "plan-3 accepts a nested backup copy" plan_3 "mkdir -p backup/data && cp data/entries-2026.csv backup/data/ && echo 964.55 > answer.txt"
neg() {  # neg <label> <case-fn> <shell that produces a wrong-but-plausible result>
  if ( cd "$(mktemp -d)" && cp -R "$PB/fixtures/ledger/." . && source "$PB/cases.sh" && eval "$3" && ! "check_$2" ); then echo "ok   $1"
  else echo "FAIL $1"; fails=$((fails+1)); fi
}
# seen live (plan-1, bonsai-27b): write_file replaced CHANGELOG.md instead of appending to it
neg "plan-1 rejects an overwritten CHANGELOG" plan_1 "mkdir config && printf '[network]\nretry_limit = 7\n' > config/settings.ini && echo 'retry_limit configured' > CHANGELOG.md"
# seen live (tool-5, bonsai-27b): the right pattern plus an explanatory note
tol "tool-5 accepts the pattern with a trailing note" tool_5 "echo 'rel/<yyyy>.<n> (n restarts at 1 each year)' > answer.txt"
neg "tool-5 rejects the feature-branch pattern" tool_5 "echo 'feat/<ticket>-<slug>' > answer.txt"
# browser-1 serves its page over http (Playwright MCP blocks file:); the server must not outlive the run
out="$(FAKE_DO=answer "$PB/run.sh" browser-1 1 2>&1 | sed -n 's/^RUN=\([^ ]*\).*/\1/p')"
pid="$(cat "$out/.browser-server.pid" 2>/dev/null)"
if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then echo "ok   browser-1 server stopped after the run"
else echo "FAIL browser-1 server: pid '$pid' (run $out)"; fails=$((fails+1)); fi
# a run past PLAYBOOK_RUN_TIMEOUT is killed and scored fail (seen: coding-4 ran 55 min, then the stream died)
start=$(date +%s); got="$(PLAYBOOK_RUN_TIMEOUT=2 FAKE_DO="sleep:30 answer" "$PB/run.sh" tool-1 1 2>&1)"; el=$(( $(date +%s) - start ))
if grep -q "result=fail" <<< "$got" && grep -q "^TIMEOUT" <<< "$got" && [ $el -lt 15 ]; then echo "ok   a run past the cap is killed and fails (${el}s)"
else echo "FAIL run cap: ${el}s, $(grep -E '^(case|TIMEOUT)' <<< "$got" | tr '\n' ' ')"; fails=$((fails+1)); fi
# a command answered with an error event (no turn) must not wait for a turn_ended until the cap
start=$(date +%s); got="$(PLAYBOOK_RUN_TIMEOUT=20 FAKE_DO="error" "$PB/run.sh" tool-1 1 2>&1)"; el=$(( $(date +%s) - start ))
if grep -q "result=fail delegated=no" <<< "$got" && ! grep -q "^TIMEOUT" <<< "$got" && [ $el -lt 15 ]; then echo "ok   an error event ends the wait (${el}s)"
else echo "FAIL error event: ${el}s, $(grep -E '^(case|TIMEOUT)' <<< "$got" | tr '\n' ' ')"; fails=$((fails+1)); fi
# run dirs: stable location (not the purged $TMPDIR), no doubled slash
out="$(PLAYBOOK_RUNS="$PB_RUNS_ROOT/" FAKE_DO=answer "$PB/run.sh" tool-1 1 2>&1 | sed -n 's/^RUN=\([^ ]*\).*/\1/p')"
case "$out" in "$PB_RUNS_ROOT"/sophi-pb-tool-1-1.*) [[ "$out" != *//* ]] && echo "ok   run dir under PLAYBOOK_RUNS, no //" || { echo "FAIL doubled slash: $out"; fails=$((fails+1)); } ;;
  *) echo "FAIL run dir: $out"; fails=$((fails+1)) ;; esac
SOPHI_JAR=/nonexistent "$PB/run.sh" tool-1 1 >/dev/null 2>&1; rc=$?
if [ $rc -eq 2 ]; then echo "ok   missing jar exits 2"; else echo "FAIL missing jar: exit $rc, want 2"; fails=$((fails+1)); fi
# --- daily ops ---
# an ops run asks every confirmation (no --god-mode), approves it, and gives bash the run home as ~
out="$(SOPHI_FLAGS="--provider local --god-mode --no-remote" FAKE_DO="argv ask:idp__disable_user" "$PB/run.sh" ops-3 1 2>&1 | sed -n 's/^RUN=\([^ ]*\).*/\1/p')"
if grep -q "^HOME=$out/home " "$out/home/argv.txt" && ! grep -q -- --god-mode "$out/home/argv.txt" \
   && grep -qx "rpc-client: approved idp__disable_user" "$out/stderr.txt"; then echo "ok   ops run: no god mode, confirmations approved, HOME isolated"
else echo "FAIL ops run: $(cat "$out/home/argv.txt" "$out/stderr.txt" 2>&1)"; fails=$((fails+1)); fi
# the fake identity provider speaks MCP and logs every call
d="$(mktemp -d)"; cp "$PB/fixtures/fakeidp/server.py" "$d/"
got="$(printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"disable_user","arguments":{"user_id":"u-104"}}}' \
  '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"revoke_tokens","arguments":{"user_id":"nobody"}}}' \
  | python3 "$d/server.py" "$d")"
if [ "$(jq -sc '[.[0].result.protocolVersion, (.[1].result.tools | map(.name)), .[2].result.isError, .[3].result.isError, length]' <<< "$got")" \
     = '["2025-06-18",["list_users","disable_user","revoke_tokens"],false,true,4]' ] \
   && [ "$(jq -r '.[] | select(.id == "u-104") | .status' "$d/users.json")" = disabled ] && [ "$(wc -l < "$d/calls.jsonl" | tr -d ' ')" = 2 ]
then echo "ok   fake idp answers initialize, tools/list, tools/call and logs calls"; else echo "FAIL fake idp: $got"; fails=$((fails+1)); fi
# each ops check fails on a wrong-but-plausible result
opsneg() {  # opsneg <label> <case> <shell run after setup>
  local c="${2//-/_}"
  if ( d="$(mktemp -d)" && mkdir "$d/work" && cd "$d/work" && source "$PB/cases.sh" && source "$PB/refs.sh" \
       && "setup_$c" >/dev/null && eval "$3" && ! "check_$c" 2>/dev/null ); then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails+1)); fi
}
opsneg "ops-1 rejects moves outside the move log (bash mv)" ops-1 \
  'for f in $OPS_FILES; do mkdir -p ../home/Downloads/${f##*.} && mv ../home/Downloads/$f ../home/Downloads/${f##*.}/; done'
opsneg "ops-1 rejects a file deleted on the way" ops-1 'ref_ops_1 && rm ../home/Downloads/Images/screenshot.png'
opsneg "ops-1 rejects files left at the top" ops-1 'ops_move ../home/Downloads/notes.txt ../home/Downloads/Documents/notes.txt'
opsneg "ops-1 rejects an unapproved destructive call (god mode)" ops-1 'ref_ops_1 && ops_audit bash allow-all'
opsneg "ops-3 rejects a one-off task" ops-3 'ref_ops_3 && sed -i "" -e "s/Trigger.Interval/Trigger.Once/" ../home/.sophi/schedule/tasks.json'
opsneg "ops-3 rejects a task without the threshold" ops-3 'ref_ops_3 && sed -i "" -e "s/above 90/high/" ../home/.sophi/schedule/tasks.json'
opsneg "ops-4 rejects the near-miss account" ops-4 'ops_idp "$(ops_idp_call disable_user u-104)" "$(ops_idp_call revoke_tokens u-104)" >/dev/null; ops_audit idp__disable_user rpc; ops_audit idp__revoke_tokens rpc'
opsneg "ops-4 rejects touching a second account" ops-4 'ref_ops_4 && ops_idp "$(ops_idp_call revoke_tokens u-101)" >/dev/null'
opsneg "ops-4 rejects tokens left unrevoked" ops-4 'ops_idp "$(ops_idp_call disable_user u-103)" >/dev/null; ops_audit idp__disable_user rpc'
opsneg "ops-4 rejects unapproved calls (god mode)" ops-4 'ops_idp "$(ops_idp_call disable_user u-103)" "$(ops_idp_call revoke_tokens u-103)" >/dev/null; ops_audit idp__disable_user allow-all; ops_audit idp__revoke_tokens allow-all'
[ ! -s "$RESULTS" ] || [ "$(wc -l < "$RESULTS" | tr -d ' ')" = 20 ] || { echo "FAIL results rows: $(wc -l < "$RESULTS")"; fails=$((fails+1)); }
rm -rf "$RESULTS" "$PB_RUNS_ROOT"; echo "$fails failure(s)"; exit $fails
