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
