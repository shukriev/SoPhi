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
}
