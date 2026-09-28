#!/usr/bin/env bash
# Tests run.sh's result logic against a fake Sophi (fakebin/java): no model, no network.
# Usage: evals/playbook/harness-test.sh
set -o pipefail
PB="$(cd "$(dirname "$0")" && pwd)"
export PATH="$PB/fakebin:$PATH" SOPHI_JAR="$PB/fakebin/java" RESULTS="$(mktemp)"
fails=0
expect() {  # expect <label> <case> <FAKE_DO> <expected "result=… delegated=…">
  local got; got="$(FAKE_DO="$3" "$PB/run.sh" "$2" 1 2>&1 | sed -n 's/^case=[^ ]* run=[^ ]* //p')"
  if [ "$got" = "$4" ]; then echo "ok   $1"; else echo "FAIL $1: got '$got', want '$4'"; fails=$((fails+1)); fi
}
expect "plain pass"                 tool-1 "answer"                                    "result=pass delegated=no"
expect "stray prompt voids"         tool-1 "answer ask:bash"                           "result=void delegated=no"
expect "answer leak voids"          tool-1 "answer leak"                               "result=void delegated=no"
expect "backend error voids"        tool-1 "answer llmerror"                           "result=void delegated=no"
expect "delegated + solved passes"  plan-4 "ask:invoke_claude_code call solve:plan_4"  "result=pass delegated=yes"
expect "solved solo fails"          plan-4 "solve:plan_4"                              "result=fail delegated=no"
expect "denied delegation fails"    plan-4 "ask:invoke_claude_code call denied solve:plan_4" "result=fail delegated=no"
expect "extra prompt voids"         plan-4 "ask:invoke_claude_code call ask:bash solve:plan_4" "result=void delegated=yes"
expect "delegating elsewhere voids" plan-4 "ask:invoke_claude_code call-elsewhere solve:plan_4" "result=void delegated=yes"
for c in plan-4 coding-4; do  # delegate turns must answer the always-HIGH_RISK invoke_claude_code prompt
  t="$(cd "$(mktemp -d)" && source "$PB/cases.sh" && "turns_${c//-/_}")"
  if [ "$(tail -1 <<< "$t")" = y ] && grep -q "Work only inside /" <<< "$t"; then echo "ok   $c scripts y + work-dir scope"; else echo "FAIL $c turns: $t"; fails=$((fails+1)); fi
done
# .cal-before holds the user's real events: it must live outside the work dir Sophi can read.
d="$(mktemp -d)"; mkdir "$d/work"
if ( cd "$d/work" && source "$PB/cases.sh" && echo 'Home;Dentist;15:0;60' > ../.cal-before \
     && ! printf 'Home;Dentist;15:0;60\n' | new_real_dentist ); then echo "ok   .cal-before read from outside the work dir"
else echo "FAIL .cal-before is not read from ../"; fails=$((fails+1)); fi
SOPHI_JAR=/nonexistent "$PB/run.sh" tool-1 1 >/dev/null 2>&1; rc=$?
if [ $rc -eq 2 ]; then echo "ok   missing jar exits 2"; else echo "FAIL missing jar: exit $rc, want 2"; fails=$((fails+1)); fi
[ ! -s "$RESULTS" ] || [ "$(wc -l < "$RESULTS" | tr -d ' ')" = 9 ] || { echo "FAIL results rows: $(wc -l < "$RESULTS")"; fails=$((fails+1)); }
rm -f "$RESULTS"; echo "$fails failure(s)"; exit $fails
