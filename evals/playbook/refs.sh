# Reference solutions, used ONLY by selftest.sh to prove each check can pass. Never shown to Sophi.

ref_tool_1() { echo parse_amount > answer.txt; }
ref_tool_2() { grep -rl total_by_category --include='*.py' . | xargs perl -pi -e 's/\btotal_by_category\b/totals_by_category/g'; }
ref_tool_3() { echo 7 > answer.txt; }
ref_tool_4() { echo "Key words for use in RFCs to Indicate Requirement Levels" > answer.txt; }

ref_plan_1() { mkdir -p config && printf '[network]\nretry_limit = 7\n' > config/settings.ini && echo 'retry_limit configured' >> CHANGELOG.md; }
ref_plan_2() { mkdir -p reports/2026 && for m in 01 02 03 04 05 06 07 08 09 10 11 12; do : > "reports/2026/$m.md"; done; }
ref_plan_3() { mkdir -p backup && cp data/entries-2026.csv backup/ && echo 964.55 > answer.txt; }
ref_plan_4() {
  cat > ledger/money.py <<'PY'
from decimal import Decimal


def parse_amount(text):
    return Decimal(text.strip()).quantize(Decimal("0.01"))


def format_amount(amount):
    return f"{Decimal(amount).quantize(Decimal('0.01'))}"
PY
  cat > tests/test_money.py <<'PY'
import unittest
from decimal import Decimal
from ledger.money import parse_amount, format_amount


class MoneyTest(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(parse_amount("12.50"), Decimal("12.50"))
        self.assertEqual(parse_amount("3"), Decimal("3.00"))

    def test_format(self):
        self.assertEqual(format_amount(Decimal("-0.05")), "-0.05")
PY
  cat > ledger/budget.py <<'PY'
import csv
from decimal import Decimal
from ledger.report import total_by_category


def over_budget(entries, budgets):
    totals = total_by_category(entries)
    return {c: totals[c] - limit for c, limit in budgets.items() if totals.get(c, 0) > limit}


def load_budgets(path):
    with open(path, newline="") as f:
        return {r["category"]: Decimal(r["limit"]) for r in csv.DictReader(f)}
PY
  cat > ledger/cli.py <<'PY'
import sys
from ledger.budget import load_budgets, over_budget
from ledger.money import format_amount
from ledger.report import load_entries, total_by_category


def main(argv):
    entries = load_entries(argv[1])
    for category, amount in sorted(total_by_category(entries).items()):
        print(f"{category}: {format_amount(amount)}")
    print(f"TOTAL: {format_amount(sum(a for _, a in entries))}")
    if "--budgets" in argv:
        for category, over in sorted(over_budget(entries, load_budgets(argv[argv.index("--budgets") + 1])).items()):
            print(f"{category}: over by {format_amount(over)}")


if __name__ == "__main__":
    main(sys.argv)
PY
}

ref_coding_1() { perl -pi -e 's/\{cents % 100\}/{cents % 100:02d}/' ledger/money.py; }
ref_coding_2() {
  cat >> ledger/report.py <<'PY'


def average_by_category(entries):
    sums, counts = {}, {}
    for category, cents in entries:
        sums[category] = sums.get(category, 0) + cents
        counts[category] = counts.get(category, 0) + 1
    return {c: (2 * sums[c] + counts[c]) // (2 * counts[c]) for c in sums}
PY
  cat >> tests/test_report.py <<'PY'


class AverageTest(unittest.TestCase):
    def test_average(self):
        from ledger.report import average_by_category
        self.assertEqual(average_by_category([("a", 100), ("a", 201)]), {"a": 151})
PY
}
ref_coding_3() { perl -pi -e 's/frac = frac\[:2\]/frac = (frac + "00")[:2]/' ledger/money.py; }
ref_coding_4() {
  mkdir -p ledger/report
  cat > ledger/report/loading.py <<'PY'
import csv
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from ledger.money import parse_amount


def load_rows(path):
    """(category, cents, currency) in the entry's own currency."""
    with open(path, newline="") as f:
        return [(r["category"], parse_amount(r["amount"]), (r.get("currency") or "EUR").strip() or "EUR")
                for r in csv.DictReader(f)]


def load_rates(path):
    rates_file = Path(path).parent / "rates.csv"
    rates = {"EUR": Decimal(1)}
    if rates_file.exists():
        with open(rates_file, newline="") as f:
            rates.update({r["currency"]: Decimal(r["eur_rate"]) for r in csv.DictReader(f)})
    return rates


def load_entries(path):
    rates = load_rates(path)
    return [(cat, int((Decimal(cents) * rates[cur]).quantize(Decimal(1), ROUND_HALF_UP)))
            for cat, cents, cur in load_rows(path)]
PY
  cat > ledger/report/aggregate.py <<'PY'
def total_by_category(entries):
    totals = {}
    for category, cents in entries:
        totals[category] = totals.get(category, 0) + cents
    return totals
PY
  printf 'from ledger.report.loading import load_entries\nfrom ledger.report.aggregate import total_by_category\n' > ledger/report/__init__.py
  rm ledger/report.py
  cat > ledger/cli.py <<'PY'
import sys
from ledger.money import format_amount
from ledger.report import load_entries, total_by_category
from ledger.report.loading import load_rows


def main(argv):
    if "--by-currency" in argv:
        per = {}
        for _, cents, cur in load_rows(argv[1]):
            per[cur] = per.get(cur, 0) + cents
        for cur, cents in sorted(per.items()):
            print(f"{cur}: {format_amount(cents)}")
        return
    entries = load_entries(argv[1])
    for category, cents in sorted(total_by_category(entries).items()):
        print(f"{category}: {format_amount(cents)}")
    print(f"TOTAL: {format_amount(sum(c for _, c in entries))}")


if __name__ == "__main__":
    main(sys.argv)
PY
}

add_event_osa() { cat <<'OSA'
on run argv
  set d to current date
  set day of d to 1
  set year of d to (item 1 of argv) as integer
  set month of d to (item 2 of argv) as integer
  set day of d to (item 3 of argv) as integer
  set time of d to 15 * hours
  tell application "Calendar" to make new event at end of events of calendar "Sophi Eval" with properties {summary:"Dentist", start date:d, end date:d + 3600}
end run
OSA
}
ref_assistant_1() { local d; d="$(next_tuesdays | head -1)"; add_event_osa | osascript - ${d//-/ } >/dev/null; }
ref_assistant_2() { echo "Renew passport before 15 October" > answer.txt; }
ref_assistant_3() { echo 09:15 > answer.txt; }
ref_assistant_4() { arcadedb_latest > answer.txt; }

ref_tool_5() { echo 'rel/<yyyy>.<n>' > answer.txt; }
ref_assistant_5() {
  mkdir -p ../home/.sophi/schedule && cat > ../home/.sophi/schedule/tasks.json <<'JSON'
[{"name": "Morning calendar review", "prompt": "Remind the user to review today's calendar",
  "trigger": {"type": "cron", "expression": "0 8 * * 1-5"}, "mode": "recurring"}]
JSON
}
ref_browser_1() { echo SO-2044 > answer.txt; }
