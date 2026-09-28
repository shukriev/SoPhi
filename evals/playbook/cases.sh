# Playbook cases: one setup_/turns_/check_ triple per case (ids use '-', functions '_').
# Sourced by run.sh and selftest.sh. Functions run with the case's work dir as cwd and $PB set.
# turns_ prints one line per user turn; a turns_<fn>_2 function means a second, separate session.
# setup_ returning 77 means "prerequisite missing on this machine" — skip, not fail.

CASES=()
DELEGATE_CASES=(plan-4 coding-4)
CALENDAR_CASES=(assistant-1)   # the only cases whose Sophi may reach Calendar.app (see guardbin/osascript)

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
check_plan_1() { grep -q '^\[network\]' config/settings.ini 2>/dev/null && grep -Eq '^retry_limit *[=:] *7 *$' config/settings.ini && grep -qx 'retry_limit configured' CHANGELOG.md && [ "$(head -1 CHANGELOG.md)" = "# Changelog" ]; }

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
  for f in $(find backup -name '*.csv' 2>/dev/null); do cmp -s "$f" data/entries-2026.csv && ok=0; done
  [ $ok -eq 0 ] && answer_is 964.55
}

# delegate: sized to exceed the local model; pass also requires invoke_claude_code (run.sh). The trailing
# 'y' answers invoke_claude_code's always-HIGH_RISK confirmation prompt.
setup_plan_4() { :; }
turns_plan_4() { echo "/goal Migrate the whole ledger package from integer cents to decimal.Decimal amounts: parse_amount must return a Decimal like Decimal('12.50'), format_amount must take a Decimal, every other function and the CLI (python3 -m ledger.cli data/entries-2026.csv) must keep working, and update the existing tests to the new types. The full test suite must pass. Work only inside $PWD."; echo y; }
check_plan_4() { suite_passes && cli_ok && hidden_passes plan_4_test; }

# --- coding ---------------------------------------------------------------------------------
CASES+=(coding-1 coding-2 coding-3 coding-4)

setup_coding_1() { perl -pi -e 's/\{cents % 100:02d\}/{cents % 100}/' ledger/money.py; }
turns_coding_1() { echo "The test suite (python3 -m unittest discover -s tests -t .) is failing. Fix the code so it passes. Do not modify anything under tests/."; }
check_coding_1() { suite_passes && tests_untouched; }

setup_coding_2() { :; }
turns_coding_2() { echo "Add a function average_by_category(entries) to ledger/report.py. It takes the same (category, cents) entries as total_by_category and returns a dict of category -> average in integer cents, rounded half up. Add tests for it in tests/test_report.py."; }
check_coding_2() { grep -q average_by_category tests/test_report.py && suite_passes && hidden_passes coding_2_test; }

# The bug crashes on the "misc,3" row (no fraction); the traceback is handed over as a file
# because stdin is one-line-per-turn.
setup_coding_3() {
  perl -pi -e 's/frac = \(frac \+ "00"\)\[:2\]/frac = frac[:2]/' ledger/money.py
  PYTHONDONTWRITEBYTECODE=1 python3 -m ledger.cli data/entries-2026.csv >/dev/null 2> crash.log
  grep -q ValueError crash.log
}
turns_coding_3() { echo "Running python3 -m ledger.cli data/entries-2026.csv crashes; the traceback is in crash.log. Find the root cause and fix it in the code, not by changing the data."; }
check_coding_3() { cli_ok && suite_passes && cmp -s data/entries-2026.csv "$PB/fixtures/ledger/data/entries-2026.csv"; }

# delegate: cross-module refactor; pass also requires invoke_claude_code (run.sh).
setup_coding_4() {
  printf 'currency,eur_rate\nUSD,0.92\nGBP,1.17\n' > data/rates.csv
  printf 'category,amount,currency\nfood,10.00,USD\nrent,100.00,EUR\ntravel,20.00,GBP\nfood,5.00,\nmisc,0.50,GBP\n' > data/entries-multi.csv
}
turns_coding_4() { echo "Add multi-currency support to the ledger and restructure it. (1) CSV entries may carry an optional third column 'currency' (empty or missing means EUR); exchange rates are in rates.csv next to the entries file (columns currency,eur_rate). load_entries must return amounts converted to EUR cents, rounding half up. (2) The CLI keeps its current output (converted to EUR) and gains a --by-currency flag, used as 'python3 -m ledger.cli <file> --by-currency', that prints one line per currency, sorted, like 'USD: 10.00', in the original currency. (3) Split ledger/report.py into a package ledger/report/ with loading.py (load_entries) and aggregate.py (total_by_category); existing imports such as 'from ledger.report import load_entries, total_by_category' must keep working. The existing test suite and the current CLI output for data/entries-2026.csv must stay the same. Work only inside $PWD."; echo y; }
check_coding_4() {
  [ ! -e ledger/report.py ] && grep -q "def load_entries" ledger/report/loading.py 2>/dev/null \
    && grep -q "def total_by_category" ledger/report/aggregate.py && suite_passes && cli_ok \
    && hidden_passes coding_4_test
}

# --- personal assistant ---------------------------------------------------------------------
CASES+=(assistant-1 assistant-2 assistant-3 assistant-4)

# Prints Y-M-D of the Tuesday "next Tuesday" means relative to $1 (default today); on a Monday it
# can fairly mean tomorrow or in 8 days, so both are printed.
next_tuesdays() { python3 - "$@" <<'PY'
import datetime as dt, sys
t = dt.date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else dt.date.today()
n = t + dt.timedelta(days=(1 - t.weekday()) % 7 or 7)
print(n.year, n.month, n.day, sep="-")
if t.weekday() == 0:
    m = n + dt.timedelta(days=7); print(m.year, m.month, m.day, sep="-")
PY
}

# Lists "calendar;summary;H:M;duration-minutes" for every event on the given Y M D.
events_osa() { cat <<'OSA'
on run argv
  set d0 to current date
  set day of d0 to 1
  set year of d0 to (item 1 of argv) as integer
  set month of d0 to (item 2 of argv) as integer
  set day of d0 to (item 3 of argv) as integer
  set time of d0 to 0
  set d1 to d0 + 86400
  set out to ""
  with timeout of 600 seconds -- big Exchange/iCloud calendars exceed the 120 s AppleEvent default
    tell application "Calendar"
      repeat with c in calendars
        repeat with e in (every event of c whose start date >= d0 and start date < d1)
          set s to start date of e
          set out to out & (name of c) & ";" & (summary of e) & ";" & (hours of s) & ":" & (minutes of s) & ";" & (((end date of e) - s) div 60) & linefeed
        end repeat
      end repeat
    end tell
  end timeout
  return out
end run
OSA
}
list_tuesday_events() { local d; for d in $(next_tuesdays); do events_osa | osascript - ${d//-/ }; done; }

setup_assistant_1() {
  osascript -e 'tell application "Calendar" to get name of calendar "Sophi Eval"' >/dev/null 2>&1 || {
    echo "Create a calendar named 'Sophi Eval' in Calendar.app first" >&2; return 77; }
  osascript -e 'tell application "Calendar" to delete (every event of calendar "Sophi Eval")' >/dev/null
  list_tuesday_events | grep -v '^Sophi Eval;' > ../.cal-before   # the user's real events: kept outside the work dir Sophi reads
}
turns_assistant_1() { echo "Put a dentist appointment on my Sophi Eval calendar for next Tuesday at 3pm, one hour long."; }
# Succeeds if any stdin line is absent from ../.cal-before (explicit loop: `grep -f` on an empty
# pattern file behaves differently between BSD and GNU grep).
new_real_dentist() { local l; while IFS= read -r l; do grep -qxF -- "$l" ../.cal-before || return 0; done; return 1; }
check_assistant_1() {
  local ev; ev="$(list_tuesday_events)" || return 1
  # a dentist event that is new AND not on Sophi Eval means Sophi wrote to a real calendar
  grep -v '^Sophi Eval;' <<< "$ev" | grep -i dentist | new_real_dentist && return 1
  grep -Eiq '^Sophi Eval;[^;]*dentist[^;]*;15:0;60$' <<< "$ev"
}

# driven, two sessions: the commitment is stated in session 1 and must come back in session 2.
setup_assistant_2() { :; }
turns_assistant_2() { echo "I need to renew my passport before the 15th of October."; }
turns_assistant_2_2() { echo "What commitments do I have open? Write each one on its own line in answer.txt."; }
check_assistant_2() { grep -qi passport answer.txt 2>/dev/null; }

setup_assistant_3() { :; }
turns_assistant_3() { echo "For future reference: my manager's name is Dana Okafor and our team standup is at 9:15 every weekday."; }
turns_assistant_3_2() { echo "What time is my team standup? Write just the time as HH:MM to answer.txt."; }
check_assistant_3() { answer_is 09:15 || answer_is 9:15; }

arcadedb_latest() { curl -fsS https://api.github.com/repos/ArcadeData/arcadedb/releases/latest | python3 -c 'import sys, json; print(json.load(sys.stdin)["tag_name"].lstrip("v"))'; }
setup_assistant_4() { [ -n "$BRAVE_SEARCH_API_KEY" ] || { echo "BRAVE_SEARCH_API_KEY not set — web_search is disabled" >&2; return 77; }; }
turns_assistant_4() { echo "Use a web search to find the latest release version of ArcadeDB (the multi-model database) and write just the version number to answer.txt."; }
check_assistant_4() { local want; want="$(arcadedb_latest)" && [ -n "$want" ] && [ "$(tr -d '[:space:]v' < answer.txt 2>/dev/null)" = "$want" ]; }

# --- grown from real sessions (2026-09-28 harvest) ------------------------------------------
CASES+=(tool-5 assistant-5 browser-1)

# Real pattern: read_file("~/…") fails (no ~ expansion), an absolute path "escapes working directory",
# and bash's ~ is the REAL home — the skill tool is the only way in. The skill exists only in the run home.
setup_tool_5() {
  mkdir -p ../home/.sophi/skills && cat > ../home/.sophi/skills/team-conventions.md <<'MD'
---
title: team-conventions
description: Branch, commit and release naming conventions for the user's team
version: 1.0.0
---

# Team conventions

- Feature branches: `feat/<ticket>-<slug>`
- Release branches: `rel/<yyyy>.<n>` (n restarts at 1 each year)
- Commit messages follow Conventional Commits.
MD
}
# The prompt must not name the skill: in the real sessions Sophi had to find it itself.
turns_tool_5() { echo "I wrote down my team's conventions for you earlier. How must our release branches be named? Write just the naming pattern to answer.txt."; }
check_tool_5() { head -1 answer.txt 2>/dev/null | tr -d '`' | grep -qE '^[[:space:]]*rel/<yyyy>\.<n>([[:space:]]|$)'; }

# Real pattern: manage_scheduled_task is the most-used tool, with real "'mode' must be recurring or
# goal" errors. The task store lives in the run home.
setup_assistant_5() { :; }
turns_assistant_5() { echo "Every weekday at 8am, remind me to review my calendar for the day."; }
check_assistant_5() {
  grep -rqE '"0 8 \* \* (1-5|MON-FRI|mon-fri)"' ../home/.sophi/schedule 2>/dev/null && grep -rqi calendar ../home/.sophi/schedule
}

# Real pattern: browser work through the Playwright MCP server. The run gets its own MCP config
# (cwd .sophi/mcp.json) with an --isolated, headless profile — never the user's real browser profile.
BROWSER_BIN="${PLAYBOOK_BROWSER:-/Applications/Brave Browser.app/Contents/MacOS/Brave Browser}"
setup_browser_1() {
  command -v npx >/dev/null && [ -x "$BROWSER_BIN" ] || { echo "needs npx and a Chromium browser (PLAYBOOK_BROWSER)" >&2; return 77; }
  # Playwright MCP blocks file: URLs, so the page is served over http on a free local port.
  local port; port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
  cp -R "$PB/fixtures/site" site && echo "$port" > ../.browser-port
  python3 -m http.server "$port" --bind 127.0.0.1 --directory site >/dev/null 2>&1 &
  echo $! > ../.browser-server.pid
  mkdir -p .sophi && cat > .sophi/mcp.json <<JSON
{"servers": [{"name": "browser", "transport": "stdio",
  "command": ["npx", "-y", "@playwright/mcp@latest", "--executable-path", "$BROWSER_BIN", "--isolated", "--headless"],
  "safeTools": ["browser_navigate", "browser_snapshot", "browser_click", "browser_type", "browser_fill_form", "browser_press_key", "browser_wait_for"]}]}
JSON
}
teardown_browser_1() { kill "$(cat ../.browser-server.pid 2>/dev/null)" 2>/dev/null; }
turns_browser_1() { echo "Open http://127.0.0.1:$(cat ../.browser-port)/index.html in the browser, search for the customer Grace Hopper, and write the ID of her open order to answer.txt."; }
check_browser_1() { answer_is SO-2044; }
