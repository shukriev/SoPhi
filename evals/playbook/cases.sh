# Playbook cases: one setup_/turns_/check_ triple per case (ids use '-', functions '_').
# Sourced by run.sh and selftest.sh. Functions run with the case's work dir as cwd and $PB set.
# turns_ prints one line per user turn; a turns_<fn>_2 function means a second, separate session.
# setup_ returning 77 means "prerequisite missing on this machine" — skip, not fail.

CASES=()
DELEGATE_CASES=(plan-4 coding-4)

suite_passes() { PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -t . >/dev/null 2>&1; }
answer_is() { [ "$(tr -d '[:space:]' < answer.txt 2>/dev/null)" = "$1" ]; }
tests_untouched() { diff -r -x __pycache__ tests "$PB/fixtures/ledger/tests" >/dev/null; }
cli_ok() { [ "$(PYTHONDONTWRITEBYTECODE=1 python3 -m ledger.cli data/entries-2026.csv 2>/dev/null | tail -1)" = "TOTAL: 964.55" ]; }
hidden_passes() {
  cp "$PB/checks/$1.py" tests/test_zz_hidden.py
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -q tests.test_zz_hidden >/dev/null 2>&1
  local rc=$?; rm -f tests/test_zz_hidden.py; return $rc
}

# --- tool use -------------------------------------------------------------------------------
CASES+=(tool-1 tool-2 tool-3 tool-4)

setup_tool_1() { :; }
turns_tool_1() { echo "Which function in the ledger package converts a string like '12.50' into integer cents? Write only the function name to answer.txt."; }
check_tool_1() { answer_is parse_amount; }

setup_tool_2() { :; }
turns_tool_2() { echo "Rename the function total_by_category to totals_by_category everywhere in this project, including its callers and tests. The test suite (python3 -m unittest discover -s tests -t .) must still pass."; }
check_tool_2() { ! grep -rq total_by_category --include='*.py' . && grep -q "def totals_by_category" ledger/report.py && suite_passes; }

setup_tool_3() { :; }
turns_tool_3() { echo "Open docs/operations/ops-notes.md and write the retry limit it specifies (just the number) to answer.txt."; }
check_tool_3() { answer_is 7; }

setup_tool_4() { :; }
turns_tool_4() { echo "Fetch https://www.rfc-editor.org/rfc/rfc2119.txt and write the RFC's title (one line) to answer.txt."; }
check_tool_4() { grep -qi "key words for use in rfcs to indicate requirement levels" answer.txt 2>/dev/null; }

# --- planning -------------------------------------------------------------------------------
CASES+=(plan-1 plan-2 plan-3 plan-4)

setup_plan_1() { :; }
turns_plan_1() { echo "/goal Read the retry limit from docs/ops.md, then create config/settings.ini containing a [network] section with retry_limit set to that value, then append the line 'retry_limit configured' to CHANGELOG.md."; }
check_plan_1() { grep -q '^\[network\]' config/settings.ini 2>/dev/null && grep -Eq '^retry_limit *= *7$' config/settings.ini && grep -qx 'retry_limit configured' CHANGELOG.md; }

# driven: the second line is the answer to the clarifying question Sophi should ask first.
setup_plan_2() { :; }
turns_plan_2() {
  echo "Set up the reports folder for me."
  echo "Put it under reports/2026, with one empty Markdown file per month named 01.md through 12.md."
}
check_plan_2() {
  [ "$(ls reports/2026 2>/dev/null | grep -Ec '^(0[1-9]|1[0-2])\.md$')" = 12 ] && [ "$(ls reports/2026 | wc -l | tr -d ' ')" = 12 ]
}

# data/entries.csv does not exist (the real file is data/entries-2026.csv): the first step fails
# and the plan has to recover.
setup_plan_3() { :; }
turns_plan_3() { echo "/goal Copy data/entries.csv into a backup/ folder, then compute the grand total of the amount column and write it (two decimals) to answer.txt."; }
check_plan_3() {
  local f ok=1
  for f in backup/*.csv; do cmp -s "$f" data/entries-2026.csv && ok=0; done
  [ $ok -eq 0 ] && answer_is 964.55
}

# delegate: sized to exceed the local model; pass also requires invoke_claude_code (run.sh).
setup_plan_4() { :; }
turns_plan_4() { echo "/goal Migrate the whole ledger package from integer cents to decimal.Decimal amounts: parse_amount must return a Decimal like Decimal('12.50'), format_amount must take a Decimal, every other function and the CLI (python3 -m ledger.cli data/entries-2026.csv) must keep working, and update the existing tests to the new types. The full test suite must pass."; }
check_plan_4() { suite_passes && cli_ok && hidden_passes plan_4_test; }
