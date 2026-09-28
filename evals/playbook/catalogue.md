# Case catalogue

Prompt, setup and check for every case live in `cases.sh` (`turns_<id>`, `setup_<id>`,
`check_<id>`) and nowhere else, so they cannot drift from what actually runs. This file is
the human index: what each case is for, and what a good run looks like.

The last three cases come from real failures in `~/.sophi/sessions` (harvested 2026-09-28);
their prompts are generalised, and no real data is copied.

| id | area | mode | done when | expected tools | notes |
|---|---|---|---|---|---|
| tool-1 | tool | solo | `answer.txt` = `parse_amount` | grep, read_file, write_file | easiest possible; a failure here is almost always harness |
| tool-2 | tool | solo | no `total_by_category` left, suite green | grep, edit_file, bash | 4 files incl. tests and `__init__` re-export |
| tool-3 | tool | solo | `answer.txt` = `7` | read_file (fails), glob/grep, read_file | the prompt's path is deliberately wrong; real file is `docs/ops.md` |
| tool-4 | tool | solo | title line in `answer.txt` | fetch_url, write_file | network |
| plan-1 | plan | solo | `config/settings.ini` + CHANGELOG line | `/goal`, read_file, write_file, edit_file | 3 steps, each depending on the last |
| plan-2 | plan | driven | 12 month files under `reports/2026` | write_file/bash | score **did it ask before creating anything** — the check can't see that |
| plan-3 | plan | solo | backup copy + `answer.txt` = `964.55` | `/goal`, glob, bash | step 1 fails by design; score the re-plan |
| plan-4 | plan | delegate | Decimal migration, suite + CLI + hidden test green, **and** `invoke_claude_code` used | `/goal`, invoke_claude_code | solved solo = `case-bug` (not hard enough); a scripted `y` answers the always-HIGH_RISK delegation prompt |
| assistant-1 | assistant | solo | 60-min dentist event, Sophi Eval, next Tuesday 15:00 | get_current_datetime, list_calendars, create_calendar_event | a new *dentist* event on any other calendar = fail (other events syncing in mid-run are ignored) |
| assistant-2 | assistant | driven (2 sessions) | `answer.txt` mentions passport | list_open_commitments | needs `--memory`; tests the encoder's commitment flag |
| assistant-3 | assistant | driven (2 sessions) | `answer.txt` = `09:15` | memory recall | reading old session files instead of memory = note in evidence |
| assistant-4 | assistant | solo | version = GitHub latest release | web_search, write_file | needs `BRAVE_SEARCH_API_KEY`; fetch_url-only = note |
| coding-1 | coding | solo | suite green, `tests/` untouched | bash, read_file, edit_file | editing tests = fail |
| coding-2 | coding | solo | function + own tests + hidden test green | read_file, edit_file, bash | half-up rounding is the trap |
| coding-3 | coding | solo | CLI total `964.55`, suite green, data untouched | read_file(crash.log), edit_file, bash | root cause is in `money.py`, not the CSV |
| coding-4 | coding | delegate | report package, old imports work, hidden test green, **and** `invoke_claude_code` used | invoke_claude_code | solved solo = `case-bug`; a scripted `y` answers the delegation prompt |
| tool-5 | tool | solo | `answer.txt` = `rel/<yyyy>.<n>` | skill | real pattern: `read_file("~/…")` → not found, absolute path → "escapes working directory", then bash retries against the *real* `$HOME`; the `skill` tool is the only way in |
| assistant-5 | assistant | solo | a cron `0 8 * * 1-5` task mentioning the calendar in the run's task store | manage_scheduled_task | real pattern: most-used tool, with real `'mode' must be recurring or goal` errors |
| browser-1 | browser | solo | `answer.txt` = `SO-2044` | browser_navigate, browser_type, browser_click, browser_snapshot | the run's own `.sophi/mcp.json` launches Playwright MCP `--isolated --headless` (never your real browser profile); reading the HTML source instead of using the browser = note in evidence |
